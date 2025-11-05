package za.ac.ukzn.ipsnavigation.data

import android.content.Context
import android.net.wifi.ScanResult
import android.util.Log
import za.ac.ukzn.ipsnavigation.utils.FeatureVectorBuilder
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Native model inference for Wi-Fi localization.
 * Uses referencepoints.csv exported from Python to perform
 * weighted KNN regression on-device.
 *
 * File structure expected:
 * assets/model/referencepoints.csv
 * columns: bssid_1,bssid_2,...,x,y
 */
class ModelInference(private val context: Context) {

    private val featureBuilder = FeatureVectorBuilder(context)
    private val referenceVectors = mutableListOf<FloatArray>()
    private val referenceCoords = mutableListOf<Pair<Double, Double>>()
    private val K = 3  // Number of nearest neighbors

    init {
        loadReferenceData()
    }

    /**
     * Load the reference fingerprint database (from Python).
     */
    private fun loadReferenceData() {
        try {
            val inputStream = context.assets.open("model/referencepoints.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val header = reader.readLine()?.split(",") ?: return
            val numFeatures = header.size - 2

            var lineCount = 0
            reader.forEachLine { line ->
                val parts = line.split(",")
                if (parts.size < numFeatures + 2) return@forEachLine

                val features = FloatArray(numFeatures) { i -> parts[i].toFloatOrNull() ?: -100f }
                val x = parts[numFeatures].toDoubleOrNull() ?: 0.0
                val y = parts[numFeatures + 1].toDoubleOrNull() ?: 0.0

                referenceVectors.add(features)
                referenceCoords.add(Pair(x, y))
                lineCount++
            }

            reader.close()
            Log.i("ModelInference", "✅ Loaded $lineCount reference points from CSV")
        } catch (e: Exception) {
            Log.e("ModelInference", "❌ Failed to load referencepoints.csv", e)
        }
    }

    /**
     * Run prediction given live Wi-Fi scan results.
     */
    fun predictFromWifi(scanResults: List<ScanResult>): Pair<Float, Float>? {
        if (referenceVectors.isEmpty()) {
            Log.e("ModelInference", "❌ No reference vectors loaded.")
            return null
        }

        val liveVector = featureBuilder.buildVector(scanResults)
        val distances = mutableListOf<Pair<Double, Int>>()

        for (i in referenceVectors.indices) {
            val ref = referenceVectors[i]
            val d = euclideanDistance(liveVector, ref)
            distances.add(Pair(d, i))
        }

        val nearest = distances.sortedBy { it.first }.take(K)

        if (nearest.isEmpty()) {
            Log.w("ModelInference", "⚠️ No nearby points found")
            return null
        }

        // Weighted average of top-K coordinates
        var sumWeights = 0.0
        var sumX = 0.0
        var sumY = 0.0

        for ((dist, idx) in nearest) {
            val weight = 1.0 / (dist + 1e-6) // avoid division by zero
            val (x, y) = referenceCoords[idx]
            sumWeights += weight
            sumX += weight * x
            sumY += weight * y
        }

        val predX = (sumX / sumWeights).toFloat()
        val predY = (sumY / sumWeights).toFloat()

        Log.d("ModelInference", "📍 Predicted Position -> x=$predX , y=$predY")
        return Pair(predX, predY)
    }

    private fun euclideanDistance(v1: FloatArray, v2: FloatArray): Double {
        var sum = 0.0
        for (i in v1.indices) {
            val diff = (v1[i] - v2[i])
            sum += diff * diff
        }
        return sqrt(sum)
    }

    fun close() {
        referenceVectors.clear()
        referenceCoords.clear()
    }
}
