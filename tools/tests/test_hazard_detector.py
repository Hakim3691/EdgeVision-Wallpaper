"""Host-side tests for the Python detector. No Android or TFLite needed."""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "app" / "src" / "main" / "python"))

import hazard_detector as detector_module  # noqa: E402


class FakeBackend:
    name = "fake"
    input_size = 320

    def __init__(self, outputs):
        self.outputs = outputs
        self.closed = False

    def run(self, rgb):
        assert len(rgb) == 320 * 320 * 3
        return self.outputs

    def describe(self):
        return {"backend": self.name, "input_size": self.input_size, "output_shapes": None}

    def close(self):
        self.closed = True


def make_outputs(detections, count=25):
    boxes = [0.0] * (count * 4)
    classes = [0.0] * count
    scores = [0.0] * count
    for index, (class_index, score, box) in enumerate(detections[:count]):
        classes[index] = float(class_index)
        scores[index] = float(score)
        boxes[index * 4:index * 4 + 4] = [float(value) for value in box]
    return [boxes, classes, scores, [float(len(detections))]]


class DecodeTests(unittest.TestCase):
    def test_vehicle_is_mapped_and_returned(self):
        outputs = make_outputs([(2, 0.91, (0.1, 0.1, 0.7, 0.6))])
        detections = detector_module._decode_outputs(outputs)
        self.assertEqual(len(detections), 1)
        self.assertEqual(detections[0]["label"], "car")
        self.assertEqual(detections[0]["hazard"], "vehicle")
        self.assertAlmostEqual(detections[0]["score"], 0.91, places=3)
        self.assertEqual(detections[0]["box"], [0.1, 0.1, 0.6, 0.7])

    def test_pole_like_classes_are_mapped(self):
        for class_index, label in (
            (8, "traffic light"),
            (9, "fire hydrant"),
            (11, "stop sign"),
            (12, "parking meter"),
        ):
            detections = detector_module._decode_outputs(
                make_outputs([(class_index, 0.8, (0.2, 0.2, 0.6, 0.8))])
            )
            self.assertEqual(detections[0]["hazard"], "pole")
            self.assertEqual(detections[0]["label"], label)

    def test_non_hazard_and_low_confidence_are_filtered(self):
        outputs = make_outputs([
            (0, 0.99, (0.1, 0.1, 0.5, 0.5)),   # person
            (2, 0.20, (0.1, 0.1, 0.9, 0.9)),   # car below threshold
            (2, 0.75, (0.4, 0.4, 0.6, 0.6)),   # car
        ])
        detections = detector_module._decode_outputs(outputs, min_score=0.35)
        self.assertEqual(len(detections), 1)
        self.assertEqual(detections[0]["label"], "car")

    def test_invalid_box_is_skipped(self):
        outputs = make_outputs([(2, 0.9, (0.6, 0.6, 0.6, 0.6))])
        self.assertEqual(detector_module._decode_outputs(outputs), [])

    def test_classes_and_scores_order_guard(self):
        outputs = make_outputs([(2, 0.9, (0.1, 0.1, 0.5, 0.5))])
        swapped = [outputs[0], outputs[2], outputs[1], outputs[3]]
        detections = detector_module._decode_outputs(swapped)
        self.assertEqual(len(detections), 1)
        self.assertEqual(detections[0]["label"], "car")

    def test_count_limits_results(self):
        outputs = make_outputs(
            [(2, 0.9, (0.1, 0.1, 0.5, 0.5)), (6, 0.9, (0.5, 0.5, 0.9, 0.9))],
            count=25,
        )
        outputs[3] = [1.0]
        self.assertEqual(len(detector_module._decode_outputs(outputs)), 1)


class DetectorTests(unittest.TestCase):
    def test_detect_returns_contract_fields(self):
        backend = FakeBackend(make_outputs([(2, 0.9, (0.1, 0.1, 0.5, 0.6))]))
        detector = detector_module.Detector(backend=backend)

        detections = detector.detect(b"\x00" * (320 * 320 * 3), 320, 320)

        self.assertEqual(detector.describe()["backend"], "fake")
        self.assertEqual(detections[0]["hazard"], "vehicle")
        json.dumps(detections)  # ensures the payload is serializable

    def test_rejects_wrong_frame_size(self):
        detector = detector_module.Detector(backend=FakeBackend(make_outputs([])))
        with self.assertRaises(ValueError):
            detector.detect(b"\x00" * 100, 320, 320)
        with self.assertRaises(ValueError):
            detector.detect(b"\x00" * (320 * 320 * 3), 160, 160)

    def test_shutdown_closes_backend(self):
        backend = FakeBackend(make_outputs([]))
        detector_module._detector = detector_module.Detector(backend=backend)
        try:
            detector_module.shutdown()
            self.assertTrue(backend.closed)
            self.assertIsNone(detector_module._detector)
        finally:
            detector_module._detector = None


class HazardMappingTests(unittest.TestCase):
    def test_vehicle_set(self):
        for index in (1, 2, 3, 5, 6, 7):
            self.assertEqual(detector_module._hazard_for_class(index), "vehicle")

    def test_unknown_class(self):
        self.assertIsNone(detector_module._hazard_for_class(0))
        self.assertIsNone(detector_module._hazard_for_class(4))


if __name__ == "__main__":
    unittest.main()
