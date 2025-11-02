package za.ac.ukzn.ipsnavigation.data

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.DataType
import java.io.FileNotFoundException
import java.nio.ByteBuffer

/**
 * Handles loading and running the TFLite Wi-Fi fingerprinting model.
 * This version runs safely even if model.tflite is missing (for testing).
 */
class ModelInference(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var isLoaded = false
    private val labels = mutableListOf<String>()

    init {
        try {
            val modelBuffer: ByteBuffer = FileUtil.loadMappedFile(context, "model.tflite")
            interpreter = Interpreter(modelBuffer)
            isLoaded = true
            Log.i("ModelInference", "✅ TFLite model loaded successfully.")
        } catch (e: FileNotFoundException) {
            Log.w("ModelInference", "⚠️ model.tflite not found. Running in safe (no-model) mode.")
        } catch (e: Exception) {
            Log.e("ModelInference", "❌ Failed to load model: ${e.message}")
        }

        // Load possible output labels if available (optional file)
        try {
            labels.addAll(FileUtil.loadLabels(context, "labels.txt"))
            Log.i("ModelInference", "Loaded ${labels.size} output labels.")
        } catch (e: Exception) {
            // If no labels.txt, define a fallback list
            labels.addAll(listOf("C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10"))
            Log.w("ModelInference", "⚠️ No labels.txt found — using fallback labels.")
        }
    }

    /**
     * Predicts the most probable location node label given an RSSI vector.
     * Returns null if the model is not loaded or prediction fails.
     */
    fun predictLocation(rssiVector: FloatArray): String? {
        if (!isLoaded || interpreter == null) {
            Log.w("ModelInference", "⚠️ No model loaded — returning simulated output for testing.")
            return "C7" // simulated output for testing
        }

        return try {
            val input = arrayOf(rssiVector)
            val output = Array(1) { FloatArray(labels.size) }
            interpreter!!.run(input, output)
            val maxIndex = output[0].indices.maxByOrNull { output[0][it] } ?: -1
            if (maxIndex in labels.indices) labels[maxIndex] else null
        } catch (e: Exception) {
            Log.e("ModelInference", "❌ Prediction failed: ${e.message}")
            null
        }
    }

    /**
     * Predicts the top two most probable node labels (for midpoint interpolation).
     */
    fun predictTop2(rssiVector: FloatArray): List<Pair<String, Float>> {
        if (!isLoaded || interpreter == null) {
            // Simulated fallback for testing
            return listOf("C7" to 0.55f, "C8" to 0.45f)
        }

        return try {
            val inputTensor = TensorBuffer.createFixedSize(intArrayOf(1, rssiVector.size), DataType.FLOAT32)
            inputTensor.loadArray(rssiVector)

            val output = Array(1) { FloatArray(labels.size) }
            interpreter!!.run(arrayOf(rssiVector), output)
            val confidences = output[0]

            val total = confidences.sum().takeIf { it != 0f } ?: 1f
            val normalized = confidences.map { it / total }

            val top2 = normalized
                .mapIndexed { index, value -> index to value }
                .sortedByDescending { it.second }
                .take(2)

            top2.map { (index, confidence) ->
                val label = labels.getOrElse(index) { "Unknown" }
                label to confidence
            }
        } catch (e: Exception) {
            Log.e("ModelInference", "❌ Top-2 prediction failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Clean up resources.
     */
    fun close() {
        try {
            interpreter?.close()
            Log.i("ModelInference", "🧹 Interpreter closed.")
        } catch (e: Exception) {
            Log.e("ModelInference", "Error closing interpreter: ${e.message}")
        }
    }
}
