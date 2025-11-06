package za.ac.ukzn.ipsnavigation.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log
import android.net.wifi.ScanResult

class WifiScanReceiver(private val callback: (List<ScanResult>) -> Unit) : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
            val wifiManager = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val results = wifiManager.scanResults
            Log.d("WifiScanReceiver", "📡 Scan complete: ${results.size} results.")
            callback(results)
        }
    }
}
