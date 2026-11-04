"""On-device hazard detection pipeline for the Edge Vision wallpaper.

Kotlin passes a center-cropped 320x320 RGB byte buffer. This module runs the
bundled EfficientDet-Lite0 model and returns only vehicle / pole-like detections
as JSON. No image data leaves the device.

The module has two interchangeable interpreters:

* ``tflite_runtime`` when a compatible Android wheel is bundled (desktop and
  custom builds). Chaquopy currently ships it only for Python 3.8 / TFLite 2.5.
* ``TfliteBridge``, a tiny Kotlin class that wraps the official TensorFlow Lite
  Java runtime, on modern Chaquopy. Python still owns preprocessing, decoding,
  filtering, and the hazard taxonomy.

The returned JSON contract is consumed by DetectionParser.kt:
    {
      "backend": "tflite_java",
      "input_size": 320,
      "latency_ms": 42.1,
      "detections": [
        {"label": "car", "hazard": "vehicle", "score": 0.82,
         "box": [0.1, 0.2, 0.5, 0.7]}
      ]
    }
"""

from __future__ import annotations

import json
import threading
import time

INPUT_SIZE = 320

# EfficientDet-Lite0 COCO label indices (0-based, matching labelmap.txt).
VEHICLE_CLASSES = frozenset({1, 2, 3, 5, 6, 7})
POLE_CLASSES = frozenset({8, 9, 11, 12})

CLASS_NAMES = {
    1: "bicycle",
    2: "car",
    3: "motorcycle",
    5: "bus",
    6: "train",
    7: "truck",
    8: "traffic light",
    9: "fire hydrant",
    11: "stop sign",
    12: "parking meter",
}

_state_lock = threading.RLock()
_detector = None


class _PythonTfliteBackend:
    name = "tflite_runtime"

    def __init__(self, model_path: str):
        from tflite_runtime.interpreter import Interpreter  # type: ignore
        import numpy as np  # type: ignore

        self._np = np
        try:
            self._interpreter = Interpreter(model_path=model_path, num_threads=2)
        except TypeError:
            self._interpreter = Interpreter(model_path=model_path)
        self._interpreter.allocate_tensors()
        self._input = self._interpreter.get_input_details()[0]
        self._outputs = self._interpreter.get_output_details()
        self.input_size = int(self._input["shape"][1])

    def run(self, rgb: bytes):
        array = self._np.frombuffer(rgb, dtype=self._np.uint8)
        array = array.reshape(self._input["shape"])
        self._interpreter.set_tensor(self._input["index"], array)
        self._interpreter.invoke()
        return [self._interpreter.get_tensor(detail["index"]) for detail in self._outputs]

    def describe(self):
        return {
            "backend": self.name,
            "input_size": self.input_size,
            "output_shapes": [list(detail["shape"]) for detail in self._outputs],
        }

    def close(self):
        close = getattr(self._interpreter, "close", None)
        if callable(close):
            close()


class _JavaTfliteBackend:
    name = "tflite_java"

    def __init__(self, model_path: str):
        from java import jclass  # type: ignore

        bridge_class = jclass("com.edgevision.wallpaper.inference.TfliteBridge")
        self._bridge = bridge_class(model_path)
        self.input_size = int(self._bridge.inputSize())

    def run(self, rgb: bytes):
        payload = json.loads(str(self._bridge.run(rgb)))
        return payload["outputs"]

    def describe(self):
        return {
            "backend": self.name,
            "input_size": self.input_size,
            "output_shapes": None,
        }

    def close(self):
        self._bridge.close()


def _load_backend(model_path: str):
    if not model_path:
        raise ValueError("A model path is required to initialize the detector")

    python_error = None
    try:
        return _PythonTfliteBackend(model_path)
    except Exception as error:  # ImportError or interpreter failure
        python_error = error

    try:
        return _JavaTfliteBackend(model_path)
    except Exception as java_error:
        raise RuntimeError(
            "No TFLite backend available. tflite_runtime failed: "
            f"{python_error!r}; Java bridge failed: {java_error!r}"
        ) from java_error


def _flatten_output(item):
    """Normalize numpy arrays, plain lists, and Java-bridge dicts."""
    if isinstance(item, dict):
        return list(item.get("values", []))
    reshape = getattr(item, "reshape", None)
    if callable(reshape):
        return reshape(-1).tolist()
    if hasattr(item, "tolist"):
        return item.tolist()
    return list(item)


def _split_outputs(outputs):
    """Split the EfficientDet postprocess tensors into flat lists."""
    if outputs is None or len(outputs) < 4:
        raise ValueError("Detector returned fewer than four output tensors")

    boxes = _flatten_output(outputs[0])
    classes = _flatten_output(outputs[1])
    scores = _flatten_output(outputs[2])
    count = _flatten_output(outputs[3])

    # Some exports order scores before classes. Guard against silently treating
    # confidence values as class indices.
    if scores and classes and max(scores) > 1.5 and max(classes) <= 1.0:
        classes, scores = scores, classes

    return boxes, classes, scores, count


def _hazard_for_class(class_index: int):
    if class_index in VEHICLE_CLASSES:
        return "vehicle"
    if class_index in POLE_CLASSES:
        return "pole"
    return None


def _decode_outputs(outputs, min_score: float = 0.35):
    """Decode postprocessed EfficientDet tensors into hazard detections."""
    boxes, classes, scores, count = _split_outputs(outputs)
    if len(boxes) % 4 != 0:
        raise ValueError("Box output is not a multiple of four")

    available = min(len(scores), len(classes), len(boxes) // 4)
    if count:
        requested = int(float(count[0]))
        if 0 < requested <= available:
            available = requested

    detections = []
    for index in range(available):
        score = float(scores[index])
        if score < min_score:
            continue

        class_index = int(float(classes[index]))
        hazard = _hazard_for_class(class_index)
        if hazard is None:
            continue

        box = boxes[index * 4:index * 4 + 4]
        top, left, bottom, right = (float(value) for value in box)
        left, right = sorted((max(0.0, min(1.0, left)), max(0.0, min(1.0, right))))
        top, bottom = sorted((max(0.0, min(1.0, top)), max(0.0, min(1.0, bottom))))
        if right <= left or bottom <= top:
            continue

        detections.append(
            {
                "label": CLASS_NAMES.get(class_index, f"class_{class_index}"),
                "hazard": hazard,
                "score": round(score, 4),
                "box": [
                    round(left, 5),
                    round(top, 5),
                    round(right, 5),
                    round(bottom, 5),
                ],
            }
        )

    # Prefer large, confident objects; the Kotlin tracker applies proximity.
    detections.sort(
        key=lambda item: (
            item["score"] * (item["box"][2] - item["box"][0]) * (item["box"][3] - item["box"][1]),
            item["score"],
        ),
        reverse=True,
    )
    return detections


class Detector:
    def __init__(self, model_path=None, input_size=INPUT_SIZE, backend=None):
        self._lock = threading.RLock()
        self._backend = backend if backend is not None else _load_backend(model_path)
        self._input_size = int(input_size)
        actual_size = int(getattr(self._backend, "input_size", self._input_size))
        if actual_size != self._input_size:
            raise ValueError(
                f"Model input is {actual_size}px but the pipeline expects {self._input_size}px"
            )

    @property
    def input_size(self):
        return self._input_size

    def detect(self, rgb: bytes, width: int, height: int, min_score: float = 0.35):
        if int(width) != self._input_size or int(height) != self._input_size:
            raise ValueError(
                f"Expected a {self._input_size}x{self._input_size} frame, got {width}x{height}"
            )
        rgb = bytes(rgb)
        expected_bytes = self._input_size * self._input_size * 3
        if len(rgb) != expected_bytes:
            raise ValueError(f"Expected {expected_bytes} RGB bytes, got {len(rgb)}")

        with self._lock:
            outputs = self._backend.run(rgb)
            return _decode_outputs(outputs, min_score=float(min_score))

    def describe(self):
        info = self._backend.describe()
        return {
            "backend": info.get("backend", self._backend.name),
            "input_size": self._input_size,
            "output_shapes": info.get("output_shapes"),
        }

    def close(self):
        with self._lock:
            self._backend.close()


def initialize(model_path: str, input_size: int = INPUT_SIZE):
    """Create the process-wide detector and return a JSON status payload."""
    global _detector
    with _state_lock:
        if _detector is None:
            _detector = Detector(model_path=model_path, input_size=int(input_size))
        return json.dumps(_detector.describe())


def detect(rgb, width, height, min_score=0.35):
    """Run one frame and return the JSON detection contract."""
    with _state_lock:
        detector = _detector
    if detector is None:
        raise RuntimeError("Detector has not been initialized")

    started = time.perf_counter()
    detections = detector.detect(rgb, int(width), int(height), float(min_score))
    latency_ms = (time.perf_counter() - started) * 1000.0
    return json.dumps(
        {
            "backend": detector.describe()["backend"],
            "input_size": detector.input_size,
            "latency_ms": round(latency_ms, 2),
            "detections": detections,
        }
    )


def shutdown():
    """Release the interpreter; the next initialize() rebuilds it lazily."""
    global _detector
    with _state_lock:
        detector = _detector
        _detector = None
    if detector is not None:
        detector.close()
