package za.ac.ukzn.ipsnavigation.ui

import android.Manifest
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.fragment.app.commit
import za.ac.ukzn.ipsnavigation.R
import za.ac.ukzn.ipsnavigation.data.LocationFusionManager
import za.ac.ukzn.ipsnavigation.data.ModelInference
import kotlin.math.hypot

/**
 * Handles Wi-Fi localization, model inference, and map updates.
 * Option A: uses raw RSSI vectors for on-device KNN (no scaler).
 */
class NavigationActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var modelInference: ModelInference
    private lateinit var fusion: LocationFusionManager
    private lateinit var mapFragment: MapFragment

    private val handler = Handler(Looper.getMainLooper())
    private var lastWifiCorrection: Pair<Double, Double>? = null

    private val scanIntervalMs = 8000L
    private var scanRunnable: Runnable? = null

    // Ask for location + Wi-Fi permissions
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.all { it.value }
        if (granted) {
            startWifiScanning()
        } else {
            Log.e("NavigationActivity", "❌ Location/Wi-Fi permissions denied.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        // Initialize components
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        modelInference = ModelInference(this)
        fusion = LocationFusionManager()

        // Load MapFragment dynamically
        if (savedInstanceState == null) {
            mapFragment = MapFragment()
            supportFragmentManager.commit {
                replace(R.id.fragmentContainer, mapFragment)
            }
        } else {
            mapFragment =
                supportFragmentManager.findFragmentById(R.id.fragmentContainer) as MapFragment
        }

        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )

        val missing = permissions.any {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing) {
            Log.i("NavigationActivity", "Requesting Wi-Fi/location permissions...")
            permissionLauncher.launch(permissions)
        } else {
            startWifiScanning()
        }
    }

    private fun startWifiScanning() {
        Log.i("NavigationActivity", "📡 Starting periodic Wi-Fi scanning...")
        scanRunnable = object : Runnable {
            override fun run() {
                if (ActivityCompat.checkSelfPermission(
                        this@NavigationActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e("NavigationActivity", "⚠️ No location permission; cannot scan.")
                    return
                }

                val success = wifiManager.startScan()
                if (!success) {
                    Log.w("NavigationActivity", "⚠️ Failed to start Wi-Fi scan (maybe throttled).")
                } else {
                    Log.i("NavigationActivity", "📶 Wi-Fi scan started.")
                }

                handler.postDelayed(this, scanIntervalMs)
            }
        }

        handler.post(scanRunnable!!)
        registerReceiver(
            WifiScanReceiver(::onWifiScanResults),
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )
    }

    private fun onWifiScanResults(results: List<ScanResult>) {
        if (results.isEmpty()) {
            Log.w("NavigationActivity", "⚠️ Wi-Fi scan returned no results.")
            return
        }

        Log.d("NavigationActivity", "Received ${results.size} Wi-Fi results.")
        results.take(5).forEach {
            Log.d("NavigationActivity", "➡️ ${it.SSID} (${it.BSSID}) = ${it.level} dBm")
        }

        val pred = modelInference.predictFromWifi(results)
        if (pred == null) {
            Log.e("NavigationActivity", "❌ Model inference failed.")
            return
        }

        val predX = pred.first.toDouble()
        val predY = pred.second.toDouble()

        applyWifiCorrection(predX, predY)
    }

    private fun applyWifiCorrection(predX: Double, predY: Double) {
        if (!fusion.isInitialized()) {
            fusion.resetTo(predX, predY)
            lastWifiCorrection = Pair(predX, predY)
            Log.d("NavigationActivity", "Kalman initialized with Wi-Fi at ($predX,$predY)")
        } else {
            val last = lastWifiCorrection
            val dist = if (last != null) hypot(predX - last.first, predY - last.second)
            else Double.MAX_VALUE

            if (dist > 0.25) { // 25 cm threshold for update
                fusion.correct(predX, predY)
                lastWifiCorrection = Pair(predX, predY)
                Log.d(
                    "NavigationActivity",
                    "📡 Applied Wi-Fi correction ($predX,$predY), Δ=${"%.2f".format(dist)} m"
                )
            } else {
                Log.d(
                    "NavigationActivity",
                    "⏸️ Skipped correction (Δ=${"%.2f".format(dist)} m, same position)"
                )
            }
        }

        val (x_m, y_m) = fusion.getPosition()
        Log.d("NavigationActivity", "📍 Fused position now = ($x_m, $y_m)")
        mapFragment.updateUserPosition(x_m, y_m)
    }

    override fun onDestroy() {
        super.onDestroy()
        scanRunnable?.let { handler.removeCallbacks(it) }
        modelInference.close()
    }
}
