package za.ac.ukzn.ipsnavigation.ui

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

class ZoomableImageView(context: Context, attrs: AttributeSet?) : AppCompatImageView(context, attrs) {

    private val matrixValues = Matrix()
    private var scale = 1f
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scale *= detector.scaleFactor
            scale = scale.coerceIn(0.5f, 4.0f)
            matrixValues.setScale(scale, scale, detector.focusX, detector.focusY)
            imageMatrix = matrixValues
            return true
        }
    })

    private var lastX = 0f
    private var lastY = 0f
    private var isPanning = false

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                isPanning = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPanning && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    matrixValues.postTranslate(dx, dy)
                    imageMatrix = matrixValues
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isPanning = false
        }
        return true
    }

    fun resetZoom() {
        scale = 1f
        matrixValues.reset()
        imageMatrix = matrixValues
    }
}
