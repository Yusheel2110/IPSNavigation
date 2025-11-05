package za.ac.ukzn.ipsnavigation.utils

import android.content.Context
import android.net.wifi.ScanResult
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Builds a normalized RSSI vector from live Wi-Fi scan results
 * using:
 * - bssid_map.json (BSSID -> column index)
 * - scaler_params.json (StandardScaler mean/std)
 */
class FeatureVectorBuilder(private val context: Context) {

    private val bssidOrder: List<String>
    private val mean: List<Double>
    private val scale: List<Double>

    init {
        // === Load BSSID map ===
        val bssidJson = loadJSONFromAsset("model/bssid_map.json")
        val jsonObject = JSONObject(bssidJson)

        // Keys = BSSIDs, values = indices
        bssidOrder = jsonObject.keys().asSequence()
            .sortedBy { jsonObject.getInt(it) }
            .toList()
        Log.i("FeatureVectorBuilder", "✅ Loaded ${bssidOrder.size} BSSIDs")

        // === Load scaler parameters ===
        val scalerJson = loadJSONFromAsset("model/scaler_params.json")
        val params = JSONObject(scalerJson)
        mean = params.getJSONArray("mean").let { arr -> List(arr.length()) { arr.getDouble(it) } }
        scale = params.getJSONArray("scale").let { arr -> List(arr.length()) { arr.getDouble(it) } }
        Log.i("FeatureVectorBuilder", "✅ Loaded scaler parameters (${mean.size} features)")
    }

    private fun loadJSONFromAsset(fileName: String): String {
        context.assets.open(fileName).use { input ->
            BufferedReader(InputStreamReader(input)).use { reader ->
                return reader.readText()
            }
        }
    }

    /**
     * Builds a normalized FloatArray feature vector
     * matching the model’s training feature order.
     */
    fun buildVector(scanResults: List<ScanResult>): FloatArray {
        val vector = FloatArray(bssidOrder.size) { -100f }

        // Fill vector with observed RSSI values
        for (result in scanResults) {
            val idx = bssidOrder.indexOf(result.BSSID.lowercase())
            if (idx != -1) {
                vector[idx] = result.level.toFloat()
            }
        }

        // Normalize using StandardScaler formula
        val normalized = FloatArray(vector.size)
        for (i in vector.indices) {
            normalized[i] = ((vector[i] - mean[i]) / scale[i]).toFloat()
        }

        Log.d("FeatureVectorBuilder", "🔧 Vector built (sample: ${normalized.take(5)})")
        return normalized
    }

    fun getFeatureCount(): Int = bssidOrder.size
}
