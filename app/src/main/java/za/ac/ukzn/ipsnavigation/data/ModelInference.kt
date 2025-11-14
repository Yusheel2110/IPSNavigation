package za.ac.ukzn.ipsnavigation.data

import android.content.Context
import android.net.wifi.ScanResult
import android.util.Log
import za.ac.ukzn.ipsnavigation.utils.FeatureVectorBuilder
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.sqrt

/**
 * Native model inference for Wi-Fi localization using weighted KNN on-device.
 * Expects assets/model/referencepoints.csv with header:
 *   bssid_1,bssid_2,...,x,y
 *
 * Option A workflow:
 * - Read header BSSID order from CSV (this is the canonical order).
 * - Tell FeatureVectorBuilder to use that order (so Android matches CSV).
 * - Use raw RSSI values (no scaling on Android) to compute euclidean distance.
 *
 * Extensive logging added to determine whether mismatch is on Android (ordering, missing BSSIDs)
 * or on the training side (CSV column / coordinate units).
 */
class ModelInference(private val context: Context) {

    private val featureBuilder = FeatureVectorBuilder(context)
    private val referenceVectors = mutableListOf<FloatArray>()
    private val referenceCoords = mutableListOf<Pair<Double, Double>>()
    private val K = 3  // Number of nearest neighbors

    // canonical bssid order read from CSV header (lowercased)
    private val csvBssidOrder = mutableListOf<String>()

    init {
        loadReferenceData()
    }

    private fun loadReferenceData() {
        try {
            val inputStream = context.assets.open("model/referencepoints.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))

            val headerLine = reader.readLine()
            if (headerLine == null) {
                Log.e("ModelInference", "❌ referencepoints.csv header missing")
                reader.close()
                return
            }

            val header = headerLine.split(",").map { it.trim() }
            if (header.size < 3) {
                Log.e("ModelInference", "❌ referencepoints.csv header too short: $header")
                reader.close()
                return
            }

            val numFeatures = header.size - 2
            // first numFeatures cols are BSSID column names; store canonical order (lowercase)
            csvBssidOrder.clear()
            for (i in 0 until numFeatures) csvBssidOrder.add(header[i].lowercase())

            Log.i(
                "ModelInference",
                "📋 CSV header read. feature columns=$numFeatures bssids (sample): ${csvBssidOrder.take(10)}"
            )

            // inform FeatureVectorBuilder to use same ordering
            featureBuilder.setReferenceBssidOrder(csvBssidOrder)

            var lineCount = 0
            val previewRows = mutableListOf<String>()

            reader.forEachLine { line ->
                val parts = line.split(",")
                if (parts.size < numFeatures + 2) {
                    Log.w("ModelInference", "⚠️ skipping malformed line (parts=${parts.size}): $line")
                    return@forEachLine
                }

                val features = FloatArray(numFeatures) { i ->
                    parts[i].toFloatOrNull() ?: -100f
                }
                val x = parts[numFeatures].toDoubleOrNull() ?: 0.0
                val y = parts[numFeatures + 1].toDoubleOrNull() ?: 0.0

                referenceVectors.add(features)
                referenceCoords.add(Pair(x, y))
                if (lineCount < 5) previewRows.add(features.take(10).joinToString(",", prefix = "[", postfix = "]"))
                lineCount++
            }

            reader.close()
            Log.i("ModelInference", "✅ Loaded $lineCount reference points from CSV")
            if (previewRows.isNotEmpty()) {
                Log.d("ModelInference", "Reference sample rows: ${previewRows.joinToString(" | ")}")
            }

            // Log coordinate ranges to detect normalized vs meters
            if (referenceCoords.isNotEmpty()) {
                val maxX = referenceCoords.maxOf { it.first }
                val maxY = referenceCoords.maxOf { it.second }
                val minX = referenceCoords.minOf { it.first }
                val minY = referenceCoords.minOf { it.second }
                Log.i("ModelInference", "Reference coords range x:[${minX}-${maxX}] y:[${minY}-${maxY}]")
                if (maxX <= 1.1 && maxY <= 1.1) {
                    Log.w(
                        "ModelInference",
                        "⚠️ Reference coords appear normalized (0..1). Map expects meters — check training/export."
                    )
                }
            }

            // Sanity check: ensure every reference vector matches csvBssidOrder length
            val mismatch = referenceVectors.any { it.size != csvBssidOrder.size }
            if (mismatch) {
                Log.e("ModelInference", "❌ Feature length mismatch between CSV header and reference rows")
            }

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

        // Build a raw RSSI vector using the CSV header order previously set in the featureBuilder
        val liveVector = featureBuilder.buildVector(scanResults)

        // Diagnostic logs to determine where problem is
        Log.d(
            "ModelInference",
            "Scan results count=${scanResults.size}; liveVector len=${liveVector.size}. sample=${liveVector.take(10)}"
        )
        Log.d(
            "ModelInference",
            "CSV BSSID count=${csvBssidOrder.size}; refVectorsCount=${referenceVectors.size}"
        )

        if (liveVector.isEmpty()) {
            Log.e(
                "ModelInference",
                "❌ liveVector empty — no bssid order available on Android or CSV order mismatch"
            )
            return null
        }
        if (liveVector.size != referenceVectors[0].size) {
            Log.w(
                "ModelInference",
                "⚠️ Feature length mismatch: live=${liveVector.size}, ref=${referenceVectors[0].size}"
            )
        }

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

        Log.d(
            "ModelInference",
            "📍 Predicted Position -> x=$predX , y=$predY ; nearest dists=${
                nearest.map { String.format("%.2f", it.first) }
            }"
        )
        return Pair(predX, predY)
    }

    private fun euclideanDistance(v1: FloatArray, v2: FloatArray): Double {
        var sum = 0.0
        val len = minOf(v1.size, v2.size)
        for (i in 0 until len) {
            val diff = (v1[i] - v2[i])
            sum += diff * diff
        }
        if (v1.size != v2.size) {
            sum += ((v1.size - v2.size) * 10.0)
        }
        return sqrt(sum)
    }

    fun close() {
        referenceVectors.clear()
        referenceCoords.clear()
        csvBssidOrder.clear()
    }
}
