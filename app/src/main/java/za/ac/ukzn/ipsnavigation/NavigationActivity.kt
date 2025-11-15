package za.ac.ukzn.ipsnavigation

import android.content.Intent
import android.os.*
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import za.ac.ukzn.ipsnavigation.data.GraphManager
import za.ac.ukzn.ipsnavigation.data.HeadingManager
import za.ac.ukzn.ipsnavigation.data.PDRManager
import za.ac.ukzn.ipsnavigation.models.Node
import za.ac.ukzn.ipsnavigation.ui.MapFragment
import kotlin.math.hypot
import kotlin.random.Random

/**
 * NavigationActivity — PDR-only mode with “Where Am I?”
 * ------------------------------------------------------------
 * - Press "Where Am I?" to snap to the next fake coordinate
 * - Cycles through predefined fake Wi-Fi positions
 * - “Open Wi-Fi Scanner” shows fake AP list + inferred (X,Y)
 * ------------------------------------------------------------
 */
class NavigationActivity : AppCompatActivity() {

    private lateinit var mapFragment: MapFragment
    private lateinit var graphManager: GraphManager
    private lateinit var pdrManager: PDRManager
    private lateinit var headingManager: HeadingManager

    // UI
    private lateinit var startSpinner: Spinner
    private lateinit var destSpinner: Spinner
    private lateinit var navigateButton: Button
    private lateinit var locateButton: Button
    private lateinit var openWifiPageButton: Button
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var predictedTextView: TextView
    private lateinit var instructionTextView: TextView

    // Fake Wi-Fi coordinate list (simulate sequential localization results)
    private val fakeLocations = listOf(
        Pair("Corridor", Pair(1.0170, 2.7074)),
        Pair("1-08", Pair(16.3, 4.8)),
        Pair("1-10", Pair(27.0163, 4.6369)),
        Pair("1-12", Pair(26.5, 1.0)),
        Pair("1-09", Pair(24.0, 4.4)),
        Pair("1-07", Pair(13.0, 4.8)),
        Pair("1-04", Pair(1.3, 5.0)),
        Pair("1-01", Pair(1.0, 1.0))
    )

    private var fakeIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_navigation)

        graphManager = GraphManager.getInstance(this)
        if (!graphManager.isLoaded()) graphManager.loadGraphFromAssets()

        // ===== UI refs =====
        startSpinner = findViewById(R.id.startNodeSpinner)
        destSpinner = findViewById(R.id.destinationSpinner)
        navigateButton = findViewById(R.id.navigateButton)
        locateButton = findViewById(R.id.locateButton)
        openWifiPageButton = findViewById(R.id.openWifiPageButton)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingText = findViewById(R.id.loadingText)
        predictedTextView = findViewById(R.id.predictedTextView)
        instructionTextView = findViewById(R.id.instructionTextView)

        val frag = supportFragmentManager.findFragmentById(R.id.mapFragmentContainer)
        mapFragment = if (frag is MapFragment) frag else MapFragment().also {
            supportFragmentManager.beginTransaction()
                .replace(R.id.mapFragmentContainer, it)
                .commit()
        }

        setupNodeSpinners()
        setupButtons()

        headingManager = HeadingManager(
            context = this,
            onHeadingUpdate = { heading -> mapFragment.updateUserHeading(heading) },
            enableLogging = false
        )

        pdrManager = PDRManager(this)
        startSensorManagers()
    }

    private fun setupNodeSpinners() {
        val nodes = graphManager.getAllNodes().mapNotNull { it.label }.sortedBy { it.lowercase() }
        val listWithCurrent = listOf("Current Location📍") + nodes
        val adapter = ArrayAdapter(this, R.layout.spinner_item_white_text, listWithCurrent)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        startSpinner.adapter = adapter

        val destAdapter = ArrayAdapter(this, R.layout.spinner_item_white_text, nodes)
        destAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        destSpinner.adapter = destAdapter
    }

    private fun setupButtons() {

        navigateButton.setOnClickListener {
            val startLabel = startSpinner.selectedItem?.toString()
            val destLabel = destSpinner.selectedItem?.toString()

            if (destLabel.isNullOrEmpty()) {
                Toast.makeText(this, "Select a destination.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            mapFragment.clearRoute()

            val actualStartNode = if (startLabel == "Current Location📍") {
                val lastPos = mapFragment.getCurrentPositionMeters()
                if (lastPos != null) {
                    graphManager.getAllNodes().minByOrNull {
                        hypot(it.x_m - lastPos.first, it.y_m - lastPos.second)
                    }
                } else graphManager.getNodeByLabel("1-01")
            } else graphManager.getNodeByLabel(startLabel ?: "1-01")

            if (actualStartNode == null) {
                Toast.makeText(this, "Invalid start node.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            mapFragment.snapToStartNode(actualStartNode)
            mapFragment.drawRoute(actualStartNode.label!!, destLabel)
            predictedTextView.text = "Route: ${actualStartNode.label} → $destLabel"
            instructionTextView.text = "Follow the red path to reach $destLabel."
        }

        locateButton.setOnClickListener {
            fakeLocateUser()
        }

        // ---------- Open Wi-Fi Page ----------
        openWifiPageButton.setOnClickListener {
            // The fakeIndex is always one step ahead of what's displayed.
            // We get the last shown index to ensure consistency.
            val lastShownIndex = if (fakeIndex > 0) fakeIndex - 1 else 0
            val (label, coords) = fakeLocations[lastShownIndex % fakeLocations.size]
            
            val intent = Intent(this, WifiScanActivity::class.java).apply {
                putExtra("label", label)
                putExtra("x", coords.first)
                putExtra("y", coords.second)
            }
            startActivity(intent)
        }
    }

    private fun fakeLocateUser() {
        mapFragment.clearRoute()
        loadingOverlay.visibility = View.VISIBLE
        loadingText.text = "📡 Locating..."
        predictedTextView.text = "Scanning nearby Wi-Fi..."
        instructionTextView.text = "Please wait..."

        val delay = Random.nextLong(800, 2500)

        Handler(Looper.getMainLooper()).postDelayed({
            loadingOverlay.visibility = View.GONE
            val (label, coords) = fakeLocations[fakeIndex % fakeLocations.size]
            fakeIndex++

            mapFragment.updateUserPosition(coords.first, coords.second)
            predictedTextView.text = "Current position: $label"
            instructionTextView.text = "You are here."
            Log.i("NavigationActivity", "📍 Wi-Fi position → $label @ (${coords.first}, ${coords.second})")
        }, delay)
    }

    private fun startSensorManagers() {
        headingManager.start()
        pdrManager.start { stepLength, _, heading ->
            mapFragment.onStepTaken(stepLength, heading)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { pdrManager.stop() } catch (_: Exception) {}
        try { headingManager.stop() } catch (_: Exception) {}
    }
}
