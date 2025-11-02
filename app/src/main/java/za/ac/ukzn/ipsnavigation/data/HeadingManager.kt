package za.ac.ukzn.ipsnavigation.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.roundToInt

/**
 * HeadingManager — provides smooth, accurate compass heading using the
 * fused Rotation Vector sensor. Works well on Huawei P20 Pro and similar devices.
 *
 * You can call start() to begin listening, and it will deliver heading updates (0–360°)
 * via the callback onHeadingUpdate().
 */
class HeadingManager(
    private val context: Context,
    private val onHeadingUpdate: (Float) -> Unit
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var currentHeading = 0f
    private var offset = 0f

    /** Start listening to the rotation vector sensor. */
    fun start() {
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
            Log.i("HeadingManager", "Rotation Vector sensor registered.")
        } else {
            Log.e("HeadingManager", "Rotation Vector Sensor not available on this device.")
        }
    }

    /** Stop listening. */
    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /** Sets the current heading as a zero reference (optional). */
    fun zero() {
        offset = currentHeading
        Log.d("HeadingManager", "Compass zeroed at $offset°")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        val rotationMatrix = FloatArray(9)
        val orientationVals = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationVals)

        // Convert azimuth (radians) → degrees
        var azimuth = Math.toDegrees(orientationVals[0].toDouble()).toFloat()
        azimuth = (azimuth + 360) % 360

        // Apply zero offset + smoothing
        val adjusted = ((azimuth - offset + 360) % 360)
        currentHeading = 0.9f * currentHeading + 0.1f * adjusted

        onHeadingUpdate(currentHeading)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            Log.w(
                "HeadingManager",
                "Compass accuracy low — move phone in a figure-8 motion to recalibrate."
            )
        }
    }
}
