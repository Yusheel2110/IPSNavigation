package za.ac.ukzn.ipsnavigation.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

/**
 * Handles live Wi-Fi scanning and RSSI vector preparation.
 */
class WifiScanManager(private val context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var scanResults: List<ScanResult> = emptyList()

    private val targetSSIDs = listOf("UKZNDATA", "eduroam") // Only use these APs

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            if (success) {
                scanResults = wifiManager.scanResults
            }
        }
    }

    /**
     * Begin a Wi-Fi scan and update scanResults when complete.
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!checkPermissions()) {
            Toast.makeText(context, "Wi-Fi permission denied", Toast.LENGTH_SHORT).show()
            return
        }

        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        context.registerReceiver(receiver, intentFilter)

        val started = wifiManager.startScan()
        if (!started) {
            Toast.makeText(context, "Scan failed to start", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Stop scanning and unregister broadcast receiver.
     */
    fun stopScan() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    /**
     * Returns the most recent scan results.
     */
    fun getScanResults(): List<ScanResult> = scanResults

    /**
     * Convert scan results into a normalized RSSI feature vector.
     * Only includes target SSIDs.
     */
    fun getRssiVector(allBssids: List<String>): FloatArray {
        val rssiMap = mutableMapOf<String, Float>()
        for (result in scanResults) {
            if (targetSSIDs.any { result.SSID.contains(it, ignoreCase = true) }) {
                rssiMap[result.BSSID] = result.level.toFloat()
            }
        }

        // Align to full BSSID feature set
        val vector = FloatArray(allBssids.size)
        for ((i, bssid) in allBssids.withIndex()) {
            vector[i] = rssiMap[bssid] ?: -100f // default for missing APs
        }

        // Normalize roughly to [0,1]
        return vector.map { (it + 100f) / 100f }.toFloatArray()
    }

    /**
     * Check permissions (needed for Android 10+).
     */
    private fun checkPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val wifiState = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE)
        return fineLocation == PackageManager.PERMISSION_GRANTED && wifiState == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Helper for Activity to request runtime permissions.
     */
    fun requestPermissions(activity: android.app.Activity, requestCode: Int = 101) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.CHANGE_WIFI_STATE
            ),
            requestCode
        )
    }
}
