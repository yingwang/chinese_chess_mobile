package com.yingwang.chinesechess.ai.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TensorFlow Lite wrapper for the Chinese Chess neural network.
 *
 * Loads a .tflite model from the app's assets and runs inference
 * returning (policy logits[2086], value) pairs.
 */
class TFLiteModel(context: Context, modelFileName: String = "chess_model.tflite") : Closeable {

    private val interpreter: Interpreter
    private var gpuDelegate: Any? = null

    init {
        val modelBuffer = loadModelFile(context, modelFileName)
        val options = Interpreter.Options()

        // Try GPU delegate, fall back to CPU on failure
        try {
            val gpuDelegateClass = Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
            val delegate = gpuDelegateClass.getDeclaredConstructor().newInstance()
            options.addDelegate(delegate as org.tensorflow.lite.Delegate)
            gpuDelegate = delegate
            Log.i("TFLiteModel", "GPU delegate loaded")
        } catch (e: Throwable) {
            Log.i("TFLiteModel", "GPU delegate unavailable, using CPU: ${e.message}")
            gpuDelegate = null
        }

        options.setNumThreads(4)
        interpreter = Interpreter(modelBuffer, options)
    }

    /**
     * Run inference on a single board position.
     *
     * @param boardTensor flat float array of size 15 * 10 * 9 = 1350 (CHW order)
     * @return (policyLogits, value) where policyLogits has 2086 elements and
     *         value is a scalar in [-1, 1].
     */
    fun predict(boardTensor: FloatArray): Pair<FloatArray, Float> {
        // Convert CHW (15,10,9) → NHWC (1,10,9,15) for TFLite
        val nhwc = FloatArray(boardTensor.size)
        for (c in 0 until 15) {
            for (h in 0 until 10) {
                for (w in 0 until 9) {
                    nhwc[h * 9 * 15 + w * 15 + c] = boardTensor[c * 10 * 9 + h * 9 + w]
                }
            }
        }
        val inputBuffer = ByteBuffer.allocateDirect(nhwc.size * 4).apply {
            order(ByteOrder.nativeOrder())
            for (v in nhwc) putFloat(v)
            rewind()
        }

        // Allocate outputs
        val policyOutput = Array(1) { FloatArray(MoveEncoding.NUM_ACTIONS) }
        val valueOutput = Array(1) { FloatArray(1) }

        val outputs = mapOf<Int, Any>(
            0 to policyOutput,
            1 to valueOutput
        )

        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)

        return policyOutput[0] to valueOutput[0][0]
    }

    override fun close() {
        interpreter.close()
        try {
            (gpuDelegate as? Closeable)?.close()
        } catch (_: Throwable) {}
    }

    private fun loadModelFile(context: Context, fileName: String): MappedByteBuffer {
        val assetFd = context.assets.openFd(fileName)
        val inputStream = FileInputStream(assetFd.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            assetFd.startOffset,
            assetFd.declaredLength
        )
    }
}
