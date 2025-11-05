package za.ac.ukzn.ipsnavigation.utils

import android.content.Context
import android.net.wifi.ScanResult
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.roundToInt

class FeatureVectorBuilder(context: Context) {

    private val bssidOrder: List<String>
    private val scalerMean: List<Double>
    private val scalerScale: List<Double>

    init {
        // Load BSSID order from assets/model/bssid_map.json
        val bssidJson = readAssetFile(context, "model/bssid_map.json")
        bssidOrder = JSONObject(bssidJson).getJSONArray("bssids").let { arr ->
            List(arr.length()) { i -> arr.getString(i) }
        }

        // Load scaler params
        val scalerJson = readAssetFile(context, "model/scaler_params.json")
        val scalerObj = JSONObject(scalerJson)
        scalerMean = scalerObj.getJSONArray("mean").let { arr -> List(arr.length()) { i -> arr.getDouble(i) } }
        scalerScale = scalerObj.getJSONArray("scale").let { arr -> List(arr.length()) { i -> arr.getDouble(i) } }
    }

    fun buildVector(scanResults: List<ScanResult>): FloatArray {
        val rssiMap = scanResults.associate { it.BSSID to it.level.toDouble() }
        val vector = FloatArray(bssidOrder.size)

        for (i in bssidOrder.indices) {
            val raw = rssiMap[bssidOrder[i]] ?: -100.0
            val normalized = ((raw - scalerMean[i]) / scalerScale[i])
            vector[i] = normalized.toFloat()
        }
        return vector
    }

    private fun readAssetFile(context: Context, path: String): String {
        context.assets.open(path).use { input ->
            return BufferedReader(InputStreamReader(input)).readText()
        }
    }
}
