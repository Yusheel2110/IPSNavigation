package za.ac.ukzn.ipsnavigation.utils

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.pow
import kotlin.math.sqrt

class KNNPredictor(context: Context, private val k: Int = 3) {

    private val referenceVectors = mutableListOf<FloatArray>()
    private val referencePositions = mutableListOf<Pair<Float, Float>>()

    init {
        val input = context.assets.open("model/X_train_reference.csv")
        BufferedReader(InputStreamReader(input)).useLines { lines ->
            lines.drop(1).forEach { line ->
                val values = line.split(",").map { it.toFloat() }
                referenceVectors.add(values.toFloatArray())
            }
        }

        val yInput = context.assets.open("model/y_train_reference.csv")
        BufferedReader(InputStreamReader(yInput)).useLines { lines ->
            lines.drop(1).forEach { line ->
                val values = line.split(",").map { it.toFloat() }
                referencePositions.add(Pair(values[0], values[1]))
            }
        }
    }

    fun predict(liveVector: FloatArray): Pair<Float, Float> {
        val distances = referenceVectors.mapIndexed { index, ref ->
            val dist = euclidean(ref, liveVector)
            Triple(dist, referencePositions[index].first, referencePositions[index].second)
        }.sortedBy { it.first }

        val nearest = distances.take(k)
        val weights = nearest.map { 1.0 / (it.first + 1e-6) }
        val sumWeights = weights.sum()

        val x = nearest.indices.sumOf { weights[it] * nearest[it].second } / sumWeights
        val y = nearest.indices.sumOf { weights[it] * nearest[it].third } / sumWeights

        return Pair(x.toFloat(), y.toFloat())
    }

    private fun euclidean(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += (a[i] - b[i]).pow(2)
        return sqrt(sum)
    }
}
