package za.ac.ukzn.ipsnavigation

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kotlin.random.Random

/**
 * WifiScanActivity — real Wi-Fi scan + fake inferred coordinates
 * ---------------------------------------------------------------
 * - Scans actual nearby networks and lists SSID + RSSI
 * - Still shows fake inferred coordinates from NavigationActivity
 * - Runs automatically when page opens (no button press)
 * ---------------------------------------------------------------
 */
class WifiScanActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var listView: ListView
    private lateinit var resultText: TextView
    private lateinit var headerText: TextView
    private var wifiReceiver: BroadcastReceiver? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val granted = perms.all { it.value }
            if (granted) startWifiScan()
            else Toast.makeText(this, "⚠️ Wi-Fi permission denied", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_scan)

        listView = findViewById(R.id.wifiListView)
        resultText = findViewById(R.id.resultText)
        headerText = findViewById(R.id.headerText)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        requestPermissionsAndScan()
    }

    // ===========================================================
    // Permissions + scanning
    // ===========================================================
    private fun requestPermissionsAndScan() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )

        if (perms.any {
                ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            permissionLauncher.launch(perms)
        } else startWifiScan()
    }

    private fun startWifiScan() {
        if (!wifiManager.isWifiEnabled) wifiManager.isWifiEnabled = true

        headerText.text = "📡 Scanning nearby access points..."
        wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
                if (success) displayScanResults()
                else Toast.makeText(this@WifiScanActivity, "⚠️ Wi-Fi scan failed", Toast.LENGTH_SHORT).show()
                unregisterReceiver(this)
            }
        }

        registerReceiver(wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        val ok = wifiManager.startScan()
        if (!ok) {
            Toast.makeText(this, "⚠️ Wi-Fi scan may be throttled by system.", Toast.LENGTH_SHORT).show()
            displayScanResults() // fallback
        }
    }

    // ===========================================================
    // Display actual scanned results + fake inferred coordinates
    // ===========================================================
    private fun displayScanResults() {
        val results = wifiManager.scanResults
        if (results.isEmpty()) {
            headerText.text = "No Wi-Fi networks found."
            return
        }

        headerText.text = "📶 Found ${results.size} access points"
        val adapter = ArrayAdapter(
            this,
            R.layout.list_item_white_text,
            results.map { "${it.SSID} (${it.level} dBm)" }
        )
        listView.adapter = adapter

        // Retrieve label + coordinates from intent (fake)
        val label = intent.getStringExtra("label") ?: "Unknown"
        val x = intent.getDoubleExtra("x", 0.0)
        val y = intent.getDoubleExtra("y", 0.0)

        // Add slight noise for realism
        val fakeX = x + Random.nextDouble(-0.25, 0.25)
        val fakeY = y + Random.nextDouble(-0.25, 0.25)
        resultText.text =
            "✅ Inferred position from model:\n$label  (X=${"%.2f".format(fakeX)}, Y=${"%.2f".format(fakeY)})"
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wifiReceiver != null) unregisterReceiver(wifiReceiver)
        } catch (_: Exception) {
        }
    }
}
