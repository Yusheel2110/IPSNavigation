package za.ac.ukzn.ipsnavigation.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Handles live Wi-Fi scanning and delivers latest results.
 * Fully compatible with Android 10–14 (API 29–35).
 */
class WifiScanManager(private val context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var scanResults: List<ScanResult> = emptyList()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            try {
                val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
                if (success) {
                    scanResults = wifiManager.scanResults ?: emptyList()
                    Log.i("WifiScanManager", "✅ Scan complete: ${scanResults.size} results")
                } else {
                    Log.w("WifiScanManager", "⚠️ Scan failed or returned empty.")
                }
            } catch (e: SecurityException) {
                Log.e("WifiScanManager", "❌ Missing permission to access scan results", e)
            }
        }
    }

    /**
     * Start a Wi-Fi scan asynchronously.
     */
    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!checkPermissions()) {
            mainHandler.post {
                Toast.makeText(context, "Wi-Fi permission missing", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            val started = wifiManager.startScan()

            if (started) {
                Log.i("WifiScanManager", "📡 Wi-Fi scan started…")
            } else {
                Log.w("WifiScanManager", "⚠️ Failed to start scan.")
                mainHandler.post {
                    Toast.makeText(context, "Scan could not start", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: SecurityException) {
            Log.e("WifiScanManager", "❌ Cannot start scan — missing permission", e)
        } catch (e: Exception) {
            Log.e("WifiScanManager", "❌ Unexpected error starting scan", e)
        }
    }

    fun stopScan() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    fun getScanResults(): List<ScanResult> = scanResults

    /**
     * Request runtime permissions (handles Android 13+ NEARBY_WIFI_DEVICES).
     */
    fun requestPermissions(activity: Activity, requestCode: Int = 101) {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires new permission for Wi-Fi scanning
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), requestCode)
    }

    private fun checkPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val wifi = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE)

        val nearbyOk =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
            else true

        return fine == PackageManager.PERMISSION_GRANTED &&
                wifi == PackageManager.PERMISSION_GRANTED &&
                nearbyOk
    }
}
