package za.ac.ukzn.ipsnavigation

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
 * - Coordinates are cycled through sequentially from a predefined list
 * - Clears any existing route before snapping
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
    private lateinit var loadingOverlay: LinearLayout
    private lateinit var loadingText: TextView
    private lateinit var predictedTextView: TextView
    private lateinit var instructionTextView: TextView

    // Fake Wi-Fi coordinate list (simulate sequential localization results)
    private val fakeLocations = listOf(
        Pair(3.5, 5.0),   // Near 1-01
        Pair(15.8, 5.0),   // Between 1-01 and 1-02
        Pair(6.0, 2.0),   // Mid-corridor left
        Pair(10.5, 2.5),  // Near 1-05 elevator
        Pair(14.0, 3.0),  // Mid-top corridor
        Pair(19.0, 3.0),  // Near 1-08
        Pair(24.5, 3.2),  // Near 1-10
        Pair(26.5, 1.0),  // Turning corner
        Pair(25.0, -1.5), // Down the right wing
        Pair(21.0, -2.5), // Near 1-12
        Pair(17.5, -2.8), // Approaching 1-13
        Pair(14.0, -3.5)  // End of hall
    )

    private var fakeIndex = 0 // track which fake position to use next

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
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingText = findViewById(R.id.loadingText)
        predictedTextView = findViewById(R.id.predictedTextView)
        instructionTextView = findViewById(R.id.instructionTextView)

        // ===== Map fragment =====
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

    // ============================================================
    // Populate dropdowns
    // ============================================================
    private fun setupNodeSpinners() {
        val nodes = graphManager.getAllNodes().mapNotNull { it.label }.sortedBy { it.lowercase() }
        val listWithCurrent = listOf("Current Location") + nodes
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listWithCurrent)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        startSpinner.adapter = adapter

        val destAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, nodes)
        destAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        destSpinner.adapter = destAdapter
    }

    // ============================================================
    // Handle buttons
    // ============================================================
    private fun setupButtons() {

        // ---------- Locate & Go ----------
        navigateButton.setOnClickListener {
            val startLabel = startSpinner.selectedItem?.toString()
            val destLabel = destSpinner.selectedItem?.toString()

            if (destLabel.isNullOrEmpty()) {
                Toast.makeText(this, "Select a destination.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Always clear old route before drawing
            mapFragment.clearRoute()

            var actualStartNode: Node? = null

            if (startLabel == "Current Location") {
                val lastPos = mapFragment.getCurrentPositionMeters()
                if (lastPos != null) {
                    actualStartNode = graphManager.getAllNodes().minByOrNull {
                        hypot(it.x_m - lastPos.first, it.y_m - lastPos.second)
                    }
                    Log.i("NavigationActivity", "📍 Using current position as start (${actualStartNode?.label})")
                } else {
                    actualStartNode = graphManager.getNodeByLabel("1-01")
                }
            } else {
                actualStartNode = graphManager.getNodeByLabel(startLabel!!)
            }

            if (actualStartNode == null) {
                Toast.makeText(this, "Invalid start node.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            mapFragment.snapToStartNode(actualStartNode)
            mapFragment.drawRoute(actualStartNode.label!!, destLabel)

            predictedTextView.text = "Route: ${actualStartNode.label} → $destLabel"
            instructionTextView.text = "Follow the red path to reach $destLabel."
        }

        // ---------- Where Am I (Fake coords) ----------
        locateButton.setOnClickListener {
            fakeLocateUser()
        }
    }

    // ============================================================
    // Fake coordinate cycle system
    // ============================================================
    private fun fakeLocateUser() {
        // Clear any route
        mapFragment.clearRoute()

        loadingOverlay.visibility = View.VISIBLE
        loadingText.text = "📡 Locating..."
        predictedTextView.text = "Scanning nearby Wi-Fi..."
        instructionTextView.text = "Please wait..."

        val delay = Random.nextLong(1500, 4000)

        Handler(Looper.getMainLooper()).postDelayed({
            loadingOverlay.visibility = View.GONE

            // Get the next fake coordinate (loop when reaching end)
            val fakePos = fakeLocations[fakeIndex % fakeLocations.size]
            fakeIndex++

            mapFragment.updateUserPosition(fakePos.first, fakePos.second)
            predictedTextView.text = "Current position: (${String.format("%.2f", fakePos.first)}, ${String.format("%.2f", fakePos.second)})"
            instructionTextView.text = "You are here (simulated)."

            Log.i("NavigationActivity", "📍 Fake Wi-Fi position → ${fakePos.first}, ${fakePos.second}")
            Toast.makeText(this, "📍 Positioned via simulated Wi-Fi scan", Toast.LENGTH_SHORT).show()
        }, delay)
    }

    // ============================================================
    // Sensor setup
    // ============================================================
    private fun startSensorManagers() {
        Log.i("NavigationActivity", "Starting HeadingManager + PDRManager (PDR-only mode)...")
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
