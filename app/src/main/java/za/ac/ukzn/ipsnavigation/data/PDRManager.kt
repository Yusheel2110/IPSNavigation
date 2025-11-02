package za.ac.ukzn.ipsnavigation.data

import android.content.Context
import android.hardware.*
import android.util.Log
import kotlin.math.*

/**
 * Pedestrian Dead Reckoning (PDR) using accelerometer + rotation vector sensors.
 * Emits step deltas (Δx, Δy) for use in Kalman fusion.
 */
class PDRManager(context: Context) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val accelValues = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var lastMagnitude = 0f
    private var stepCount = 0
    private var stepLength = 0.75f
    private var threshold = 1.2f
    private var lastStepTime = 0L

    var x = 0.0
    var y = 0.0
    var headingDeg = 0.0

    private var lastX = 0.0
    private var lastY = 0.0
    private var onStepCallback: ((Double, Double) -> Unit)? = null

    fun start(onStep: (Double, Double) -> Unit) {
        onStepCallback = onStep
        sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
        sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            Sensor.TYPE_ROTATION_VECTOR -> handleRotation(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun handleAccelerometer(event: SensorEvent) {
        System.arraycopy(event.values, 0, accelValues, 0, 3)
        val magnitude = sqrt(accelValues[0].pow(2) + accelValues[1].pow(2) + accelValues[2].pow(2))
        val now = System.currentTimeMillis()

        if (magnitude - lastMagnitude > threshold && now - lastStepTime > 300) {
            stepCount++
            lastStepTime = now
            updatePosition()
        }
        lastMagnitude = magnitude
    }

    private fun handleRotation(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        headingDeg = Math.toDegrees(orientation[0].toDouble())
        if (headingDeg < 0) headingDeg += 360.0
    }

    private fun updatePosition() {
        val headingRad = Math.toRadians(headingDeg)
        x += stepLength * sin(headingRad)
        y += stepLength * cos(headingRad)
        val deltaX = x - lastX
        val deltaY = y - lastY
        lastX = x
        lastY = y
        Log.d("PDR", "Step $stepCount → Δx=$deltaX, Δy=$deltaY, heading=$headingDeg°")
        onStepCallback?.invoke(deltaX, deltaY)
    }

    fun resetTo(x_m: Double, y_m: Double) {
        x = x_m
        y = y_m
        lastX = x_m
        lastY = y_m
        stepCount = 0
    }
}
