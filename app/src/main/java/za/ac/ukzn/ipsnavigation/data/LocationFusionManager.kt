package za.ac.ukzn.ipsnavigation.data

import android.util.Log

/**
 * Combines Wi-Fi absolute positions and PDR deltas using a lightweight 2D Kalman filter.
 * Keeps user motion smooth and realistic.
 */
class LocationFusionManager {

    // State [x, y, vx, vy]
    private val state = DoubleArray(4)
    private val P = Array(4) { DoubleArray(4) }

    private val processNoise = 0.05      // motion model uncertainty
    private val measurementNoise = 1.0   // Wi-Fi accuracy (meters)
    private var initialized = false

    fun resetTo(x: Double, y: Double) {
        state[0] = x
        state[1] = y
        state[2] = 0.0
        state[3] = 0.0
        for (i in 0..3) {
            for (j in 0..3) P[i][j] = if (i == j) 1.0 else 0.0
        }
        initialized = true
        Log.d("Fusion", "Kalman reset to ($x, $y)")
    }

    fun predict(deltaX: Double, deltaY: Double) {
        if (!initialized) return
        state[0] += deltaX
        state[1] += deltaY
        for (i in 0..3) P[i][i] += processNoise
    }

    fun correct(x_meas: Double, y_meas: Double) {
        if (!initialized) return
        val Kx = P[0][0] / (P[0][0] + measurementNoise)
        val Ky = P[1][1] / (P[1][1] + measurementNoise)
        state[0] += Kx * (x_meas - state[0])
        state[1] += Ky * (y_meas - state[1])
        P[0][0] *= (1 - Kx)
        P[1][1] *= (1 - Ky)
        Log.d("Fusion", "Correction → ($x_meas, $y_meas) => state=(${state[0]}, ${state[1]})")
    }

    fun getPosition(): Pair<Double, Double> {
        return state[0] to state[1]
    }
}
