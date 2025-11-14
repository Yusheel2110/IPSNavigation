package za.ac.ukzn.ipsnavigation.ui

import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import za.ac.ukzn.ipsnavigation.R
import za.ac.ukzn.ipsnavigation.data.GraphManager
import za.ac.ukzn.ipsnavigation.data.PDRManager
import za.ac.ukzn.ipsnavigation.data.PathFinder
import za.ac.ukzn.ipsnavigation.models.Node
import kotlin.math.*

/**
 * MapFragment — PDR-only mode with Kalman smoothing + eased translation + smooth arrow rotation.
 * The user dot glides smoothly along the red route and the arrow rotates naturally.
 * Arrow is hidden by default until "Where Am I?" or route chosen.
 */
class MapFragment : Fragment() {

    private lateinit var mapView: ZoomableImageView
    private lateinit var recenterButton: ImageButton
    private lateinit var graphManager: GraphManager
    private lateinit var pathFinder: PathFinder
    lateinit var pdrManager: PDRManager

    private lateinit var baseFloorplan: Bitmap
    private var currentBitmap: Bitmap? = null
    private var routePath: List<Node>? = null

    // User position + heading
    private var currentXpx = 0f
    private var currentYpx = 0f
    private var currentHeading = 0f       // display heading (smoothed)
    private var targetHeading = 0f        // last sensor heading
    private var lastRedrawTime = 0L
    private var arrowVisible = false      // hidden by default

    // Path progress
    private var currentEdgeIndex = 0
    private var progressOnEdge = 0.0

    // Step + animation config
    private val stepLength = 0.0005
    private val animationDuration = 350L

    // Conversion constants
    private val pxPerMeter = 104.03
    private val imgHeight = 2000.0
    private val offsetXMeters = 1.1
    private val offsetYMeters = 7.5

    // Light Kalman filters for pixel smoothing
    private val kalmanX = KalmanFilter(q = 0.02, r = 0.5)
    private val kalmanY = KalmanFilter(q = 0.02, r = 0.5)

    // Rotation smoothing parameters
    private val headingSmoothingFactor = 0.15f // smaller = slower turn, 0.15–0.25 feels natural

    // ============================================================
    //  Lifecycle
    // ============================================================
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_map, container, false)
        mapView = root.findViewById(R.id.zoomableMap)
        recenterButton = root.findViewById(R.id.recenterButton)
        val debugOverlay: TextView = root.findViewById(R.id.debugOverlay)

        graphManager = GraphManager.getInstance(requireContext())
        if (!graphManager.isLoaded()) graphManager.loadGraphFromAssets()
        pathFinder = PathFinder(requireContext())
        pdrManager = PDRManager(requireContext())
        pdrManager.start { stepLength, _, heading -> onStepTaken(stepLength, heading) }

        loadFloorplan()

        recenterButton.setOnClickListener {
            mapView.resetZoom()
            Toast.makeText(requireContext(), "Map recentered.", Toast.LENGTH_SHORT).show()
        }

        return root
    }

    // ============================================================
    //  Step-based motion along route
    // ============================================================
    fun onStepTaken(stepLength: Double, heading: Double) {
        val path = routePath ?: return
        if (currentEdgeIndex >= path.size - 1) return

        val start = path[currentEdgeIndex]
        val end = path[currentEdgeIndex + 1]
        val edgeLen = hypot(end.x_m - start.x_m, end.y_m - start.y_m)
        if (edgeLen <= 0.0) return

        progressOnEdge += stepLength / edgeLen

        if (progressOnEdge >= 1.0) {
            currentEdgeIndex++
            progressOnEdge = 0.0
            if (currentEdgeIndex >= path.size - 1) {
                Log.d("TRACK", "✅ Reached destination.")
                return
            }
        }

        val s = path[currentEdgeIndex]
        val e = path[currentEdgeIndex + 1]
        val x = s.x_m + (e.x_m - s.x_m) * progressOnEdge
        val y = s.y_m + (e.y_m - s.y_m) * progressOnEdge

        animateUserTo(x, y)
    }

    // ============================================================
    //  Smooth animation + Kalman filtering
    // ============================================================
    private fun animateUserTo(x_m: Double, y_m: Double) {
        val (targetX, targetY) = toPx(x_m, y_m)
        val startX = currentXpx
        val startY = currentYpx
        val startTime = System.currentTimeMillis()

        mapView.post(object : Runnable {
            override fun run() {
                val elapsed = System.currentTimeMillis() - startTime
                var t = (elapsed.toFloat() / animationDuration).coerceIn(0f, 1f)
                t = ((1 - cos(t * Math.PI)) / 2.0f).toFloat() // cosine easing

                val rawX = startX + (targetX - startX) * t
                val rawY = startY + (targetY - startY) * t

                currentXpx = kalmanX.update(rawX.toDouble()).toFloat()
                currentYpx = kalmanY.update(rawY.toDouble()).toFloat()

                smoothHeading()
                if (arrowVisible) redrawMap()
                if (t < 1f) mapView.postDelayed(this, 16L)
            }
        })
    }

    // ============================================================
    //  Heading smoothing
    // ============================================================
    fun updateUserHeading(headingDeg: Float) {
        targetHeading = headingDeg
        if (arrowVisible) {
            smoothHeading()
            val now = System.currentTimeMillis()
            if (now - lastRedrawTime > 100) {
                lastRedrawTime = now
                redrawMap()
            }
        }
    }

    private fun smoothHeading() {
        val diff = ((targetHeading - currentHeading + 540f) % 360f) - 180f
        currentHeading = (currentHeading + headingSmoothingFactor * diff + 360f) % 360f
    }

    // ============================================================
    //  Drawing
    // ============================================================
    private fun loadFloorplan() {
        val input = requireContext().assets.open("floorplan.png")
        baseFloorplan = BitmapFactory.decodeStream(input)
        currentBitmap = baseFloorplan.copy(Bitmap.Config.ARGB_8888, true)
        mapView.setImageBitmap(currentBitmap)
        Log.d("MapFragment", "✅ Floorplan loaded.")
    }

    private fun toPx(x_m: Double, y_m: Double): Pair<Float, Float> {
        val px = (x_m + offsetXMeters) * pxPerMeter
        val py = imgHeight - ((y_m + offsetYMeters) * pxPerMeter)
        return Pair(px.toFloat(), py.toFloat())
    }

    fun drawRoute(startLabel: String, goalLabel: String) {
        routePath = pathFinder.findPath(startLabel, goalLabel)
        if (routePath.isNullOrEmpty()) {
            Log.w("MapFragment", "No route found from $startLabel to $goalLabel")
            return
        }
        currentEdgeIndex = 0
        progressOnEdge = 0.0
        kalmanX.reset()
        kalmanY.reset()
        arrowVisible = true
        fadeInArrow()
        redrawMap()
        Log.d("MapFragment", "🗺️ Route drawn ($startLabel → $goalLabel)")
    }

    fun updateUserPosition(x_m: Double, y_m: Double) {
        val (xpx, ypx) = toPx(x_m, y_m)
        currentXpx = xpx
        currentYpx = ypx
        arrowVisible = true
        fadeInArrow()
        redrawMap()
    }

    private fun redrawMap() {
        val updated = baseFloorplan.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(updated)

        // Draw route
        routePath?.let { path ->
            val paint = Paint().apply {
                color = Color.RED
                strokeWidth = 8f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            for (i in 0 until path.size - 1) {
                val (rx1, ry1) = toPx(path[i].x_m, path[i].y_m)
                val (rx2, ry2) = toPx(path[i + 1].x_m, path[i + 1].y_m)
                canvas.drawLine(rx1, ry1, rx2, ry2, paint)
            }
        }

        if (arrowVisible) {
            val userPaint = Paint().apply {
                color = Color.CYAN
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            canvas.drawCircle(currentXpx, currentYpx, 18f, userPaint)
            drawHeadingArrow(canvas)
        }

        mapView.setImageBitmap(updated)
        currentBitmap = updated
    }

    private fun drawHeadingArrow(canvas: Canvas) {
        val arrowLength = 45f
        val angleRad = Math.toRadians(currentHeading.toDouble())
        val tipX = currentXpx + arrowLength * sin(angleRad).toFloat()
        val tipY = currentYpx - arrowLength * cos(angleRad).toFloat()
        val leftX = currentXpx - 15 * cos(angleRad).toFloat()
        val leftY = currentYpx - 15 * sin(angleRad).toFloat()
        val rightX = currentXpx + 15 * cos(angleRad).toFloat()
        val rightY = currentYpx + 15 * sin(angleRad).toFloat()

        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(leftX, leftY)
            lineTo(rightX, rightY)
            close()
        }

        val paint = Paint().apply {
            color = Color.BLUE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawPath(path, paint)
    }

    // ============================================================
    //  Helpers
    // ============================================================
    fun getRoutePath(): List<Node>? = routePath

    fun snapToStartNode(startNode: Node) {
        updateUserPosition(startNode.x_m, startNode.y_m)
        currentEdgeIndex = 0
        progressOnEdge = 0.0
        kalmanX.reset()
        kalmanY.reset()
        currentHeading = 0f
        targetHeading = 0f
        arrowVisible = true
        fadeInArrow()
        Log.d("MapFragment", "📍 Snapped to start node ${startNode.label}")
    }

    private fun fadeInArrow() {
        mapView.alpha = 0f
        mapView.animate().alpha(1f).setDuration(600).start()
    }

    // ============================================================
    //  Clear route + get last known position (for "Current Location")
    // ============================================================
    fun clearRoute() {
        routePath = null
        currentEdgeIndex = 0
        progressOnEdge = 0.0
        kalmanX.reset()
        kalmanY.reset()
        redrawMap()
        Log.d("MapFragment", "🧹 Cleared previous route.")
    }

    fun getCurrentPositionMeters(): Pair<Double, Double>? {
        if (currentXpx == 0f && currentYpx == 0f) return null
        val x_m = (currentXpx / pxPerMeter) - offsetXMeters
        val y_m = ((imgHeight - currentYpx) / pxPerMeter) - offsetYMeters
        return Pair(x_m, y_m)
    }

    fun simulateStep() {
        onStepTaken(stepLength, targetHeading.toDouble())
    }
}

// ============================================================
//  Lightweight 1D Kalman Filter for pixel smoothing
// ============================================================
class KalmanFilter(private val q: Double, private val r: Double) {
    private var x = 0.0
    private var p = 1.0
    private var initialized = false

    fun update(measurement: Double): Double {
        if (!initialized) {
            x = measurement
            initialized = true
        }
        p += q
        val k = p / (p + r)
        x += k * (measurement - x)
        p *= (1 - k)
        return x
    }

    fun reset() {
        p = 1.0
        initialized = false
    }
}
