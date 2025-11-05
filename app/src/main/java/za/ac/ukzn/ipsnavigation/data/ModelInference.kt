package za.ac.ukzn.ipsnavigation.data

import android.content.Context
import android.net.wifi.ScanResult
import android.util.Log
import za.ac.ukzn.ipsnavigation.utils.FeatureVectorBuilder
import za.ac.ukzn.ipsnavigation.utils.KNNPredictor

/**
 * Native KNN + Scaler inference for on-device indoor positioning.
 * Replaces the TensorFlow Lite version — 100% Kotlin & offline.
 */
class ModelInference(context: Context) {

    private val featureBuilder = FeatureVectorBuilder(context)
    private val predictor = KNNPredictor(context)

    /**
     * Runs full inference:
     *  1. Converts Wi-Fi scan results into a normalized vector
     *  2. Predicts (x, y) position using KNN distance weighting
     */
    fun predictFromWifi(scanResults: List<ScanResult>): Pair<Float, Float>? {
        return try {
            if (scanResults.isEmpty()) {
                Log.w("ModelInference", "⚠️ No Wi-Fi scan results available.")
                return null
            }

            // Build normalized feature vector
            val inputVector = featureBuilder.buildVector(scanResults)

            // Run native KNN prediction
            val (x, y) = predictor.predict(inputVector)

            Log.d("ModelInference", "📍 Predicted Position -> x=$x , y=$y")
            Pair(x, y)
        } catch (e: Exception) {
            Log.e("ModelInference", "❌ Prediction failed: ${e.message}")
            null
        }
    }

    /**
     * Optional cleanup (kept for compatibility).
     */
    fun close() {
        Log.i("ModelInference", "🧹 No resources to close in native mode.")
    }
}
