package com.edgevision.wallpaper.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Bitmap.Config
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Interpreter.Options
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.common.RectangleF
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kotlin-native TFLite interpreter for EfficientDet-Lite0.
 *
 * Replaces the Python (Chaquopy) inference pipeline to eliminate Python startup
 * overhead and reduce per-frame latency. Runs directly on the CPU Interpreter
 * with 2 threads, using the bundled EfficientDet-Lite0 model.
 *
 * Input: 320x320 RGB byte buffer in display orientation (already cropped/rotated
 * by CameraController.YuvFrameConverter).
 * Output: InferenceResult with detections, latency, and backend info.
 *
 * Preprocessing (applied inside this class, matching Python pipeline):
 * - Raw RGB bytes interpreted as NHWC uint8 [1, 320, 320, 3]
 * - TFLite EfficientDet-Lite0 expects uint8 input, scale=1/255 applied via
 *   the interpreter's normalization (see tflite model metadata).
 * Postprocessing: parse the 4 output tensors (boxes, classes, scores, count)
 * into the same contract the Python pipeline delivers, so CameraController and
 * the wallpaper service see no API change.
 */
class TfliteDetector(modelPath: String, private val inputSize: Int = 320) : AutoCloseable {

    private val interpreter: Interpreter
    private val inputBuffer: ByteBuffer
    private val outputShapes: List<IntArray>
    private val outputNames: List<String>
    private val outputDataTypes: List<DataType>

    /** Pre-allocated output buffers to avoid per-inference allocation. */
    private val outputBuffers: Map<Integer, ByteBuffer>

    /** Latency tracking over the last N invocations. */
    private val latencyMsSamples = mutableListOf<Long>()
    private const val MAX_LATENCY_SAMPLES = 16

    /** Model is loaded and ready. */
    @Volatile
    private var initialized = false

    /** Number of runs since last reset, for per-session stats. */
    private var runCount = 0

    init {
        val modelFile = File(modelPath).apply { exists() ?: error("Model file not found: $modelPath") }
        interpreter = Interpreter(modelFile, Options().setNumThreads(2))

        // Inspect input tensor
        val inputTensor = interpreter.getInputTensor(0)
        require(inputTensor.dataType() == DataType.UINT8) {
            "Expected uint8 input, got ${inputTensor.dataType()}"
        }
        val inputShape = inputTensor.shape()
        require(inputShape.size == 4 && inputShape[0] == 1 && inputShape[3] == 3) {
            "Expected NHWC uint8 [1, H, W, 3], got ${inputShape.contentToString()}"
        }
        require(inputShape[1] == inputShape[2]) {
            "Expected square input, got ${inputShape.contentToString()}"
        }
        // Verify inputSize matches model's expected dimension
        val modelInputSize = inputShape[1]
        if (modelInputSize != inputSize) {
            Log.w(
                "TfliteDetector",
                "Model input size $modelInputSize differs from configured $inputSize; " +
                "adjusting interpreter input buffer size."
            )
        }

        inputSize = modelInputSize
        inputBuffer = ByteBuffer.allocateDirect(inputSize * inputSize * 3).order(ByteOrder.nativeOrder())

        // Inspect output tensors
        outputShapes = (0 until interpreter.outputTensorCount).map { index ->
            interpreter.getOutputTensor(index).shape()
        }
        outputNames = (0 until interpreter.outputTensorCount).map { index ->
            interpreter.getOutputTensor(index).name ?: "output_$index"
        }
        outputDataTypes = (0 until interpreter.outputTensorCount).map { index ->
            interpreter.getOutputTensor(index).dataType
        }

        // Pre-allocate output buffers matching each output tensor's element count
        outputBuffers = outputShapes.indices.associateWith { idx ->
            val tensor = interpreter.getOutputTensor(idx)
            val numElements = tensor.numElements()
            val buf = ByteBuffer.allocateDirect(numElements * 4) // FloatArray = 4 bytes
            buf.order(ByteOrder.nativeOrder())
            idx to buf
        }

        interpreter.allocateTensors()
        initialized = true
        Log.i("TfliteDetector", "Initialized TFLite interpreter for EfficientDet-Lite0 " +
            inputSize + "x$inputSize, ${interpreter.outputTensorCount} outputs")
    }

    /** Returns the input size this detector expects (always 320 after init). */
    inputSize(): Int = inputSize

    /** Run one inference step on the given 320x320 RGB byte buffer. */
    @Synchronized
    fun detect(rgb: ByteArray): InferenceResult {
        require(rgb.size == inputSize * inputSize * 3) {
            "Expected ${inputSize * inputSize * 3} RGB bytes, got ${rgb.size}"
        }

        // Copy RGB bytes into the pre-allocated input buffer (no reorder needed if
        // CameraController already supplies display‑ordered RGB).
        inputBuffer.clear()
        inputBuffer.put(rgb)
        inputBuffer.rewind()

        // Record latency
        val startMs = SystemClock.uptimeMillis()
        interpreter.run(inputBuffer, outputBuffers)

        // Read output tensors into JSON‑serializable form
        val outputs = readOutputs()

        val endMs = SystemClock.uptimeMillis()
        val latency = endMs - startMs
        latencyMsSamples.add(latency)
        if (latencyMsSamples.size > MAX_LATENCY_SAMPLES) {
            latencyMsSamples.removeAt(0)
        }
        runCount++

        return InferenceResult(
            detections = outputs.detections,
            backend = "tflite_java",
            latencyMs = latency,
            inputSize = inputSize,
            avgLatencyMs = if (latencyMsSamples.isNotEmpty()) {
                latencyMsSamples.average().toLong()
            } else latency
        )
    }

    /** Read the four EfficientDet output tensors and build a detection list. */
    private fun readOutputs(): OutputData {
        // Output 0: boxes [1, 1, 100, 4] or [1, 1, N, 4] — denormalized to [left, top, right, bottom]
        val boxesBuf = outputBuffers[0] ?: run { throw IllegalStateException("Missing output 0 (boxes)") }
        val classesBuf = outputBuffers[1] ?: run { throw IllegalStateException("Missing output 1 (classes)") }
        val scoresBuf = outputBuffers[2] ?: run { throw IllegalStateException("Missing output 2 (scores)") }
        val countBuf = outputBuffers[3] ?: run { throw IllegalStateException("Missing output 3 (count)") }

        // Read float arrays
        val boxCount = outputDataTypes[3] == DataType.FLOAT32
            ? scoresBuf.capacity() / 4
            : scoresBuf.capacity()

        // Decode count (first element may be a scalar or a tensor element)
        val rawCount = if (outputShapes[3].size >= 1) {
            val countArr = outputBufToFloatArray(outputBuffers[3]!!
            outputBuffers[3]!!.rewind()
            outputDataTypes[3] == DataType.INT32
                ? outputBuffers[3]!!.getInt()
                : outputBuffers[3]!!.getFloat()
        } else 0f).toInt()

        val effectiveCount = if (rawCount > 0 && rawCount <= 100) rawCount else  // EfficientDet-Lite0 max 100 detections
            // Fall back to reading actual non-zero scores
            decodeDetections(boxesBuf, classesBuf, scoresBuf, rawCount).size

        val detections = decodeDetections(boxesBuf, classesBuf, scoresBuf, effectiveCount)

        return OutputData(detections = detections)
    }

    /** Decode the raw tensor outputs into a list of Detection objects. */
    private fun decodeDetections(
        boxesBuf: ByteBuffer,
        classesBuf: ByteBuffer,
        scoresBuf: ByteBuffer,
        count: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()

        // EfficientDet-Lite0 typically outputs [1, 1, 100, 4] for boxes,
        // but the actual number is given by count[0] or the tensor size.
        for (i in 0 until count) {
            // Read box: [y_min, x_min, y_max, x_max] or [left, top, right, bottom]
            // Layout depends on model version; EfficientDet uses [ymin, xmin, ymax, xmax] normalized
            val boxOff = i * 4
            val ymin = boxesBuf.getFloat(boxOff)
            val xmin = boxesBuf.getFloat(boxOff + 1)
            val ymax = boxesBuf.getFloat(boxOff + 2)
            val xmax = boxesBuf.getFloat(boxOff + 3)

            // Clip to [0, 1] range (model outputs normalized coordinates)
            val left = Math.min(Math.max(xmin, 0f), 1f)
            val top = Math.min(Math.max(ymin, 0f), 1f)
            val right = Math.min(Math.max(xmax, 0f), 1f)
            val bottom = Math.min(Math.max(ymax, 0f), 1f)

            // Read class index and score
            val classIdx = classesBuf.getFloat(i * 4 + 0).toInt() // classes are often ints stored as float
            val score = scoresBuf.getFloat(i * 4 + 0)

            // Map class to hazard kind (matching Python backend logic)
            val hazard = when (classIdx) {
                in { 1, 2, 3, 5, 6, 7 } -> "vehicle" // COCO vehicle classes
                in { 8, 9, 11, 12 } -> "pole" // COCO pole-like classes
                else -> null
            }

            if (hazard == null || score < 0.35f) continue // min_score filter

            detections += Detection(
                label = CLASS_NAMES[classIdx] ?: "obstacle",
                hazard = hazard,
                confidence = score,
                box = Box(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom
                )
            )
        }
        return detections
    }

    /** Convert a ByteBuffer of floats to a FloatArray. */
    private fun outputBufToFloatArray(buf: ByteBuffer): FloatArray {
        val count = buf.capacity() / 4 // each float is 4 bytes
        val arr = FloatArray(count)
        buf.rewind()
        buf.asFloatBuffer().get(arr)
        return arr
    }

    /** Summary metadata about the last inference run. */
    data class OutputData(val detections: List<Detection>)

    /** Release the interpreter and free native resources. */
    override fun close() {
        if (initialized) {
            interpreter.close()
            initialized = false
            Log.i("TfliteDetector", "TFLite interpreter closed")
        }
    }

    /** Companion with static label map matching the COCO-trained EfficientDet-Lite0 model. */
    companion object {
        private const val TAG = "TfliteDetector"

        /** COCO label indices that map to vehicle/pole hazards (EfficientDet-Lite0 trained on COCO). */
        val VEHICLE_CLASSES = setOf(1, 2, 3, 5, 6, 7)
        val POLE_CLASSES = setOf(8, 9, 11, 12)

        /** Human-readable class names for the hazard taxonomy. */
        val CLASS_NAMES = mapOf(
            1 to "car",
            2 to "bicycle",
            3 to "motorcycle",
            5 to "bus",
            6 to "train",
            7 to "truck",
            8 to "traffic light",
            9 to "fire hydrant",
            11 to "stop sign",
            12 to "parking meter"
        )
    }
}