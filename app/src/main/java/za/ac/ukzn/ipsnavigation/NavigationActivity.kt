package za.ac.ukzn.ipsnavigation

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import za.ac.ukzn.ipsnavigation.data.HeadingManager
import za.ac.ukzn.ipsnavigation.data.*
import za.ac.ukzn.ipsnavigation.models.Node
import za.ac.ukzn.ipsnavigation.ui.MapFragment
import za.ac.ukzn.ipsnavigation.utils.InstructionGenerator

/**
 * Final IPS Navigation Activity
 * Now includes Kalman fusion (Wi-Fi + PDR) and smooth heading integration.
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

    private val allBssids = listOf(
        "00:11:22:33:44:55",
        "AA:BB:CC:DD:EE:FF",
        "11:22:33:44:55:66"
    )

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

        // ===== Initialize or Attach MapFragment =====
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

            val results = wifiScanManager.getScanResults()
            if (results.isEmpty()) {
                Toast.makeText(this@NavigationActivity, "No Wi-Fi results found.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val rssiVector = wifiScanManager.getRssiVector(allBssids)
            val predictedLabel = modelInference.predictLocation(rssiVector)

            if (predictedLabel == null) {
                Toast.makeText(this@NavigationActivity, "Could not determine location.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            predictedText.text = "Current position: $predictedLabel"
            Toast.makeText(this@NavigationActivity, "You are at $predictedLabel", Toast.LENGTH_SHORT).show()

            // ===== Draw Route =====
            mapFragment.drawRoute(predictedLabel, goalLabel)

            // ===== Generate Instructions =====
            val pathFinder = PathFinder(this@NavigationActivity)
            val routeNodes: List<Node> = pathFinder.findPath(predictedLabel, goalLabel)
            if (routeNodes.isEmpty()) {
                instructionText.text = "No valid route found."
                return@launch
            }

            val instructions = InstructionGenerator.generateInstructions(this@NavigationActivity, routeNodes)
            instructionText.text = instructions.joinToString("\n• ", prefix = "• ")

            // ===== Reset and Sync Kalman + PDR =====
            val startNode = graphManager.getNodeByLabel(predictedLabel)
            if (startNode != null) {
                pdrManager.resetTo(startNode.x_m, startNode.y_m)
                fusionManager.resetTo(startNode.x_m, startNode.y_m)
                mapFragment.updateUserPosition(startNode.x_m, startNode.y_m)
            }

            // ===== Start Periodic Wi-Fi Correction Loop =====
            lifecycleScope.launch(Dispatchers.IO) {
                while (true) {
                    delay(8000)
                    wifiScanManager.startScan()
                    delay(2500)
                    val wifiResults = wifiScanManager.getScanResults()
                    if (wifiResults.isNotEmpty()) {
                        val vec = wifiScanManager.getRssiVector(allBssids)
                        val label = modelInference.predictLocation(vec)
                        label?.let {
                            val node = graphManager.getNodeByLabel(it)
                            if (node != null) {
                                fusionManager.correct(node.x_m, node.y_m)
                            }
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
