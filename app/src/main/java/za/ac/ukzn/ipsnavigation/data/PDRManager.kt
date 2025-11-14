package za.ac.ukzn.ipsnavigation.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pedestrian Dead Reckoning (PDR) Manager
 * Detects steps and provides heading. It does not estimate position.
 */
class PDRManager(private val context: Context) : SensorEventListener {

    // --- State -- -
    var headingDeg: Double = 0.0
    var stepCount: Int = 0
    private var targetDirectionDeg: Double = -1.0

    // --- Step detection -- -
    private var sensorManager: SensorManager? = null
    private var accelValues = FloatArray(3)
    private var lastStepTime = 0L
    private val stepLength = 0.75   // meters
    private val stepThreshold = 13.5
    private val minStepInterval = 800L // ms

    // --- Heading integration -- -
    private lateinit var headingManager: HeadingManager

    // --- Optional callback for live updates -- -
    // Callback provides: stepLength, ignored (0.0), heading
    private var stepListener: ((Double, Double, Double) -> Unit)? = null

    // =====================================================
    // Public API
    // =====================================================

    fun start(onStep: ((stepLength: Double, y: Double, heading: Double) -> Unit)? = null) {
        stepListener = onStep
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        headingManager = HeadingManager(
            context,
            onHeadingUpdate = { heading ->
                headingDeg = heading.toDouble()
            },
            enableLogging = true
        )
        headingManager.start()
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        headingManager.stop()
        sensorManager = null
        stepListener = null
    }

    /**
     * Resets the internal step counter. PDRManager does not track X/Y position.
     */
    fun resetTo() {
        stepCount = 0
        // The HeadingManager resets its own filters internally.
    }

    fun setTargetDirection(deg: Double) {
        targetDirectionDeg = deg
    }

    // =====================================================
    // Sensor Handling
    // =====================================================

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            accelValues = event.values.clone()
            detectStep(accelValues)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // =====================================================
    // Step Detection Logic
    // =====================================================

    private fun detectStep(accel: FloatArray) {
        val magnitude = sqrt(accel[0] * accel[0] + accel[1] * accel[1] + accel[2] * accel[2])
        val now = System.currentTimeMillis()
        Log.d("PDR_DEBUG", "Step #$stepCount detected at ${System.currentTimeMillis()} ms, mag=${"%.2f".format(magnitude)}")


        if (magnitude > stepThreshold && (now - lastStepTime) > minStepInterval) {
            lastStepTime = now

            // --- Apply map alignment offset for heading check ---
            val mapHeadingOffset = 133.0 // From graph.json: north_angle_deg
            val alignedHeading = (headingDeg + mapHeadingOffset) % 360

            // Check if step direction is valid against the current route corridor
            if (targetDirectionDeg != -1.0) {
                val diff = ((alignedHeading - targetDirectionDeg + 540) % 360) - 180
                if (abs(diff) > 45) {
                    Log.d("PDRManager", "Step ignored due to heading. Diff: ${abs(diff).toInt()}°")
                    return // Step is not in the target direction, so ignore it.
                }
            }
            
            stepCount++
            Log.d("PDRManager", "Step #$stepCount detected. Heading: ${"%.1f".format(headingDeg)}°")
            
            // Invoke listener with constant step length and current heading.
            // The second parameter (Y) is always 0.0 as PDRManager does not track position.
            stepListener?.invoke(stepLength, 0.0, headingDeg)
        }
    }
    
    // =====================================================
    // Getter
    // =====================================================
    fun getHeading(): Double = headingDeg
}