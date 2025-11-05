package za.ac.ukzn.ipsnavigation

import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import za.ac.ukzn.ipsnavigation.data.*
import za.ac.ukzn.ipsnavigation.models.Node
import za.ac.ukzn.ipsnavigation.ui.MapFragment
import za.ac.ukzn.ipsnavigation.utils.InstructionGenerator

/**
 * Final IPS Navigation Activity
 * Updated for native KNN inference (no TensorFlow).
 * Includes Wi-Fi scanning, KNN localization, and PDR + Kalman fusion.
 */
class NavigationActivity : AppCompatActivity() {

    private lateinit var graphManager: GraphManager
    private lateinit var mapFragment: MapFragment
    private lateinit var wifiScanManager: WifiScanManager
    private lateinit var modelInference: ModelInference
    private lateinit var pdrManager: PDRManager
    private lateinit var fusionManager: LocationFusionManager
    private lateinit var headingManager: HeadingManager

    private lateinit var destinationInput: Spinner
    private lateinit var navigateButton: Button
    private lateinit var predictedText: TextView
    private lateinit var instructionText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        // ===== Initialize Core Managers =====
        graphManager = GraphManager.getInstance(this)
        if (!graphManager.isLoaded()) graphManager.loadGraphFromAssets()

        wifiScanManager = WifiScanManager(this)
        modelInference = ModelInference(this)
        pdrManager = PDRManager(this)
        fusionManager = LocationFusionManager()
        headingManager = HeadingManager(this) { headingDeg ->
            // Optionally forward to map for directional arrow
            // mapFragment.updateUserHeading(headingDeg)
        }
        headingManager.start()

        // ===== Initialize MapFragment =====
        val existing = supportFragmentManager.findFragmentById(R.id.mapFragmentContainer)
        if (existing is MapFragment) {
            mapFragment = existing
        } else {
            mapFragment = MapFragment()
            supportFragmentManager.beginTransaction()
                .replace(R.id.mapFragmentContainer, mapFragment)
                .commitNow()
        }

        // ===== Initialize UI =====
        destinationInput = findViewById(R.id.destinationSpinner)
        navigateButton = findViewById(R.id.navigateButton)
        predictedText = findViewById(R.id.predictedTextView)
        instructionText = findViewById(R.id.instructionTextView)

        val allLabels = graphManager.getAllNodes().mapNotNull { it.label }.sorted()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, allLabels)
        destinationInput.adapter = adapter

        wifiScanManager.requestPermissions(this)

        navigateButton.setOnClickListener {
            val goalLabel = destinationInput.selectedItem?.toString()
            if (goalLabel.isNullOrEmpty()) {
                Toast.makeText(this, "Select a destination", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runLocalizationAndNavigate(goalLabel)
        }

        // ===== Start PDR Tracking =====
        pdrManager.start { deltaX, deltaY ->
            fusionManager.predict(deltaX, deltaY)
            val (fusedX, fusedY) = fusionManager.getPosition()
            runOnUiThread {
                mapFragment.updateUserPosition(fusedX, fusedY)
            }
        }

        Toast.makeText(this, "Navigation initialized with Kalman fusion.", Toast.LENGTH_SHORT).show()
    }

    private fun runLocalizationAndNavigate(goalLabel: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(this@NavigationActivity, "Scanning Wi-Fi…", Toast.LENGTH_SHORT).show()
            wifiScanManager.startScan()
            delay(2500)

            val scanResults = wifiScanManager.getScanResults()
            if (scanResults.isEmpty()) {
                Toast.makeText(this@NavigationActivity, "No Wi-Fi results found.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // ==== Run Native KNN Localization ====
            val predictedPosition = modelInference.predictFromWifi(scanResults)
            if (predictedPosition == null) {
                Toast.makeText(this@NavigationActivity, "Could not determine location.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val (x, y) = predictedPosition
            Log.d("NavigationActivity", "📍 Predicted position → x=$x , y=$y")
            predictedText.text = "Current position: x=$x , y=$y"
            Toast.makeText(this@NavigationActivity, "You are at x=$x, y=$y", Toast.LENGTH_SHORT).show()

            // ===== Find nearest node to predicted position =====
            val nearestNode = graphManager.findNearestNode(x, y)
            if (nearestNode == null) {
                Toast.makeText(this@NavigationActivity, "No nearby node found.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // ===== Draw Route =====
            mapFragment.drawRoute(nearestNode.label, goalLabel)

            // ===== Generate Instructions =====
            val pathFinder = PathFinder(this@NavigationActivity)
            val routeNodes: List<Node> = pathFinder.findPath(nearestNode.label, goalLabel)
            if (routeNodes.isEmpty()) {
                instructionText.text = "No valid route found."
                return@launch
            }

            val instructions = InstructionGenerator.generateInstructions(this@NavigationActivity, routeNodes)
            instructionText.text = instructions.joinToString("\n• ", prefix = "• ")

            // ===== Reset Kalman & PDR to Predicted Location =====
            pdrManager.resetTo(x, y)
            fusionManager.resetTo(x, y)
            mapFragment.updateUserPosition(x, y)

            // ===== Periodic Wi-Fi Corrections =====
            lifecycleScope.launch(Dispatchers.IO) {
                while (true) {
                    delay(8000)
                    wifiScanManager.startScan()
                    delay(2500)
                    val wifiResults = wifiScanManager.getScanResults()
                    if (wifiResults.isNotEmpty()) {
                        val correction = modelInference.predictFromWifi(wifiResults)
                        correction?.let { (cx, cy) ->
                            fusionManager.correct(cx, cy)
                            Log.d("NavigationActivity", "📡 Correction applied → x=$cx , y=$cy")
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wifiScanManager.stopScan()
        modelInference.close()
        pdrManager.stop()
        headingManager.stop()
    }
}
