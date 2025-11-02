package za.ac.ukzn.ipsnavigation.ui

import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import za.ac.ukzn.ipsnavigation.R
import za.ac.ukzn.ipsnavigation.data.GraphManager
import za.ac.ukzn.ipsnavigation.data.PathFinder
import za.ac.ukzn.ipsnavigation.models.Node
import kotlin.math.*

/**
 * MapFragment with zoom/pan, rerouting, and now compass-based heading arrow overlay.
 */
class MapFragment : Fragment() {

    private lateinit var mapView: ZoomableImageView
    private lateinit var recenterButton: ImageButton
    private lateinit var graphManager: GraphManager
    private lateinit var pathFinder: PathFinder

    private lateinit var baseFloorplan: Bitmap
    private var currentBitmap: Bitmap? = null
    private var routePath: List<Node>? = null

    private var currentXpx = 0f
    private var currentYpx = 0f
    private var currentHeading = 0f

    private var lastGoalLabel: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_map, container, false)
        mapView = root.findViewById(R.id.zoomableMap)
        recenterButton = root.findViewById(R.id.recenterButton)

        graphManager = GraphManager.getInstance(requireContext())
        if (!graphManager.isLoaded()) graphManager.loadGraphFromAssets()
        pathFinder = PathFinder(requireContext())

        loadFloorplan()

        recenterButton.setOnClickListener {
            mapView.resetZoom()
            Toast.makeText(requireContext(), "Map recentered.", Toast.LENGTH_SHORT).show()
        }

        return root
    }

    private fun loadFloorplan() {
        val input = requireContext().assets.open("floorplan.png")
        baseFloorplan = BitmapFactory.decodeStream(input)
        currentBitmap = baseFloorplan.copy(Bitmap.Config.ARGB_8888, true)
        mapView.setImageBitmap(currentBitmap)
        Log.d("MapFragment", "Floorplan loaded.")
    }

    private fun toPx(nodeX: Double, nodeY: Double): Pair<Float, Float> {
        val meta = graphManager.getMetadata()
        val pxPerMeter = meta.scale?.px_per_meter ?: 100.0
        val imgHeight = meta.image_size_px?.height ?: 2000
        val xOff = meta.alignment_offsets_m?.x ?: 0.0
        val yOff = meta.alignment_offsets_m?.y ?: 0.0
        val px = (nodeX + xOff) * pxPerMeter
        val py = imgHeight - ((nodeY + yOff) * pxPerMeter)
        return Pair(px.toFloat(), py.toFloat())
    }

    fun drawRoute(startLabel: String, goalLabel: String) {
        lastGoalLabel = goalLabel
        val path = pathFinder.findPath(startLabel, goalLabel)
        if (path.isEmpty()) return
        routePath = path

        val mutable = baseFloorplan.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val paint = Paint().apply {
            color = Color.RED
            strokeWidth = 8f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }

        for (i in 0 until path.size - 1) {
            val (x1, y1) = toPx(path[i].x_m, path[i].y_m)
            val (x2, y2) = toPx(path[i + 1].x_m, path[i + 1].y_m)
            canvas.drawLine(x1, y1, x2, y2, paint)
        }

        mapView.setImageBitmap(mutable)
        currentBitmap = mutable
    }

    fun updateUserPosition(x_m: Double, y_m: Double) {
        val (xpx, ypx) = toPx(x_m, y_m)
        currentXpx = xpx
        currentYpx = ypx
        redrawMap()
        checkOffRoute(x_m, y_m)
    }

    /** Called from NavigationActivity → HeadingManager updates */
    /** Called from NavigationActivity → HeadingManager updates */
    fun updateUserHeading(headingDeg: Float) {
        // Apply smoothing for gradual rotation (0.1 = slower, 0.3 = faster)
        val diff = ((headingDeg - currentHeading + 540) % 360) - 180
        currentHeading = (currentHeading + diff * 0.15f + 360) % 360
        redrawMap()
    }


    /** Redraws map overlay with user position + heading arrow. */
    private fun redrawMap() {
        val updated = baseFloorplan.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(updated)

        // Draw route
        routePath?.let { path ->
            val routePaint = Paint().apply {
                color = Color.RED
                strokeWidth = 8f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            for (i in 0 until path.size - 1) {
                val (x1, y1) = toPx(path[i].x_m, path[i].y_m)
                val (x2, y2) = toPx(path[i + 1].x_m, path[i + 1].y_m)
                canvas.drawLine(x1, y1, x2, y2, routePaint)
            }
        }

        // Draw user marker (circle)
        val userPaint = Paint().apply {
            color = Color.CYAN
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(currentXpx, currentYpx, 18f, userPaint)

        // Draw heading arrow (triangle)
        drawHeadingArrow(canvas)

        mapView.setImageBitmap(updated)
        currentBitmap = updated
    }

    /** Draws a small arrow showing current heading direction */
    private fun drawHeadingArrow(canvas: Canvas) {
        val arrowLength = 45f
        val angleRad = Math.toRadians(currentHeading.toDouble())

        // Triangle points
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

        val arrowPaint = Paint().apply {
            color = Color.BLUE
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        canvas.drawPath(path, arrowPaint)
    }

    private fun checkOffRoute(x_m: Double, y_m: Double) {
        val path = routePath ?: return
        var minDist = Double.MAX_VALUE
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val dist = pointToSegmentDistance(x_m, y_m, a.x_m, a.y_m, b.x_m, b.y_m)
            minDist = minOf(minDist, dist)
        }

        if (minDist > 2.0 && lastGoalLabel != null) {
            val nearest = graphManager.getAllNodes().minByOrNull {
                hypot(it.x_m - x_m, it.y_m - y_m)
            }
            nearest?.label?.let { newStart ->
                drawRoute(newStart, lastGoalLabel!!)
            }
        }
    }

    private fun pointToSegmentDistance(
        x: Double, y: Double,
        x1: Double, y1: Double,
        x2: Double, y2: Double
    ): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        if (dx == 0.0 && dy == 0.0) return hypot(x - x1, y - y1)
        val t = ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)
        return when {
            t < 0 -> hypot(x - x1, y - y1)
            t > 1 -> hypot(x - x2, y - y2)
            else -> hypot(x - (x1 + t * dx), y - (y1 + t * dy))
        }
    }
}
