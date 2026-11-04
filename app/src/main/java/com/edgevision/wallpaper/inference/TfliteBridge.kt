package com.edgevision.wallpaper.inference

import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thin Java-side bridge used by the Python detector.
 *
 * Chaquopy currently publishes tflite-runtime only for Python 3.8 / TFLite 2.5,
 * so on modern Chaquopy the Python pipeline calls this class to obtain an
 * interpreter. All preprocessing, decoding, filtering, and hazard mapping still
 * happen in Python.
 */
class TfliteBridge(modelPath: String) : AutoCloseable {

    private val interpreter: Interpreter
    private val inputSize: Int
    private val inputBuffer: ByteBuffer
    private val outputShapes: List<IntArray>
    private val outputNames: List<String>

    init {
        val modelFile = File(modelPath)
        require(modelFile.isFile) { "Model not found: $modelPath" }
        interpreter = Interpreter(
            modelFile,
            Interpreter.Options().apply { setNumThreads(2) },
        )

        val inputTensor = interpreter.getInputTensor(0)
        require(inputTensor.dataType() == DataType.UINT8) {
            "Expected a uint8 input tensor, got ${inputTensor.dataType()}"
        }
        val inputShape = inputTensor.shape()
        require(inputShape.size == 4 && inputShape[0] == 1 && inputShape[3] == 3) {
            "Expected NHWC uint8 input, got ${inputShape.contentToString()}"
        }
        require(inputShape[1] == inputShape[2]) {
            "Expected a square detector input, got ${inputShape.contentToString()}"
        }
        inputSize = inputShape[1]
        val expectedBytes = inputSize * inputSize * 3
        require(inputTensor.numBytes() == expectedBytes) {
            "Expected $expectedBytes input bytes, got ${inputTensor.numBytes()}"
        }

        inputBuffer = ByteBuffer.allocateDirect(expectedBytes).order(ByteOrder.nativeOrder())
        (0 until interpreter.outputTensorCount).forEach { index ->
            val tensor = interpreter.getOutputTensor(index)
            require(tensor.dataType() == DataType.FLOAT32) {
                "Only float32 outputs are supported, output $index is ${tensor.dataType()}"
            }
        }
        outputShapes = (0 until interpreter.outputTensorCount).map { index ->
            interpreter.getOutputTensor(index).shape()
        }
        outputNames = (0 until interpreter.outputTensorCount).map { index ->
            interpreter.getOutputTensor(index).name() ?: "output_$index"
        }

        interpreter.allocateTensors()
    }

    fun inputSize(): Int = inputSize

    /** Runs one inference and returns the raw tensors as JSON. */
    @Synchronized
    fun run(rgb: ByteArray): String {
        require(rgb.size == inputSize * inputSize * 3) {
            "Expected ${inputSize * inputSize * 3} RGB bytes, got ${rgb.size}"
        }

        inputBuffer.rewind()
        inputBuffer.put(rgb)
        inputBuffer.rewind()
        // Null output lets TFLite keep its own dynamically shaped output tensors;
        // reading them through asReadOnlyBuffer avoids preallocating a Java array
        // whose rank must exactly match the model's postprocess tensors.
        interpreter.run(inputBuffer, null)

        val outputArray = JSONArray()
        (0 until interpreter.outputTensorCount).forEach { index ->
            val tensor = interpreter.getOutputTensor(index)
            val values = FloatArray(tensor.numElements())
            tensor.asReadOnlyBuffer()
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .get(values)

            val tensorJson = JSONObject()
            tensorJson.put("name", outputNames[index])
            tensorJson.put("shape", JSONArray(outputShapes[index].toList()))
            val valueArray = JSONArray()
            values.forEach { valueArray.put(it.toDouble()) }
            tensorJson.put("values", valueArray)
            outputArray.put(tensorJson)
        }

        return JSONObject().put("outputs", outputArray).toString()
    }

    @Synchronized
    override fun close() {
        interpreter.close()
    }
}
