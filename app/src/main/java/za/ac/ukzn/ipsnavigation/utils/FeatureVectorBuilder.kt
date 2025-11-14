package za.ac.ukzn.ipsnavigation.utils

import android.content.Context
import android.net.wifi.ScanResult
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Builds a raw RSSI vector from live Wi-Fi scan results.
 * Option A: Use raw RSSI (no StandardScaler) to match referencepoints.csv KNN distances.
 *
 * The builder will accept a reference BSSID order from ModelInference (preferred).
 * If none is provided it will attempt to load model/bssid_map.json as a fallback.
 */
class FeatureVectorBuilder(private val context: Context) {

    private var bssidOrder: List<String> = emptyList()

    init {
        // Try to load a fallback map if available (non-fatal)
        try {
            val raw = loadJSONFromAsset("model/bssid_map.json")
            val json = org.json.JSONObject(raw)
            bssidOrder = json.keys().asSequence()
                .sortedBy { json.getInt(it) }
                .map { it.lowercase() }
                .toList()
            Log.i("FeatureVectorBuilder", "✅ Fallback BSSID map loaded (${bssidOrder.size} BSSIDs)")
        } catch (e: Exception) {
            Log.w("FeatureVectorBuilder", "No fallback bssid_map.json found or failed to parse (this is OK).")
        }
    }

    private fun loadJSONFromAsset(fileName: String): String {
        context.assets.open(fileName).use { input ->
            BufferedReader(InputStreamReader(input)).use { reader ->
                return reader.readText()
            }
        }
    }

    /**
     * Called by ModelInference after reading CSV header to guarantee ordering match.
     */
    fun setReferenceBssidOrder(order: List<String>) {
        bssidOrder = order.map { it.lowercase() }
        Log.i("FeatureVectorBuilder", "📐 Reference BSSID order set (${bssidOrder.size})")
    }

    /**
     * Build raw RSSI vector aligned to bssidOrder. Missing BSSIDs -> -100f.
     */
    fun buildVector(scanResults: List<ScanResult>): FloatArray {
        if (bssidOrder.isEmpty()) {
            Log.w("FeatureVectorBuilder", "⚠️ bssidOrder is empty — buildVector will return empty array")
            return FloatArray(0)
        }

        val vector = FloatArray(bssidOrder.size) { -100f } // default for missing RSSI

        // Map scan results by lowercased BSSID for fast lookup
        val rssiMap = scanResults.associate { it.BSSID.lowercase() to it.level.toFloat() }

        for (i in bssidOrder.indices) {
            val bssid = bssidOrder[i]
            rssiMap[bssid]?.let { rssi -> vector[i] = rssi }
        }

        // Log a small sample for debugging (first 10 cols)
        val sample = vector.take(10).joinToString(",")
        Log.d("FeatureVectorBuilder", "🔧 Raw vector built (len=${vector.size}) sample: [$sample] scans=${scanResults.size}")
        return vector
    }

    fun getFeatureCount(): Int = bssidOrder.size
}
