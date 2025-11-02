package za.ac.ukzn.ipsnavigation.utils

import android.content.Context
import za.ac.ukzn.ipsnavigation.data.GraphManager
import za.ac.ukzn.ipsnavigation.models.Node
import kotlin.math.*

/**
 * Enhanced navigation instruction generator:
 * - Detects real turns using angle change.
 * - Keeps junctions for geometry but replaces them with nearby room labels.
 * - Skips repeated labels for cleaner directions.
 */
object InstructionGenerator {

    fun generateInstructions(context: Context, path: List<Node>): List<String> {
        if (path.size < 2) return listOf("Already at destination.")

        val graphManager = GraphManager.getInstance(context)
        val allNodes = graphManager.getAllNodes()
        val instructions = mutableListOf<String>()
        var lastLabel: String? = null

        for (i in 0 until path.size - 1) {
            val current = path[i]
            val next = path[i + 1]

            val dist = distance(current, next)
            val distText = if (dist > 0.5) " for ${"%.1f".format(dist)} m" else ""

            // Get next readable label (replace junctions)
            val nextLabel = when (next.type?.lowercase()) {
                "junction" -> {
                    val nearestRoom = allNodes
                        .filter { it.type?.lowercase() == "room" }
                        .minByOrNull { distance(next, it) }
                    nearestRoom?.label ?: next.label ?: "nearby room"
                }
                "turn" -> "the end of hallway" // friendly label for turn nodes
                else -> next.label ?: ""
            }

            // Skip duplicate consecutive labels
            if (nextLabel == lastLabel) continue
            lastLabel = nextLabel

            // Detect turns based on three points
            val turnInstruction = if (i >= 1 && i < path.size - 1) {
                val prev = path[i - 1]
                val angle = turnAngle(prev, current, next)
                when {
                    angle > 30 -> "Turn right"
                    angle < -30 -> "Turn left"
                    else -> "Go straight"
                }
            } else "Go straight"

            // If it’s a “turn” node, describe it explicitly
            if (next.type?.lowercase() == "turn") {
                instructions.add("Turn at $nextLabel")
            } else {
                val labelText = if (nextLabel.isNotEmpty()) " towards $nextLabel" else ""
                instructions.add("$turnInstruction$distText$labelText")
            }
        }

        val destLabel = path.lastOrNull()?.label ?: "your destination"
        instructions.add("You have arrived at $destLabel")

        return instructions
    }

    /** Euclidean distance (in meters) */
    private fun distance(a: Node, b: Node): Double =
        hypot(a.x_m - b.x_m, a.y_m - b.y_m)

    /** Calculates signed angle (positive = right turn, negative = left) */
    private fun turnAngle(a: Node, b: Node, c: Node): Double {
        val v1x = b.x_m - a.x_m
        val v1y = b.y_m - a.y_m
        val v2x = c.x_m - b.x_m
        val v2y = c.y_m - b.y_m
        val dot = v1x * v2x + v1y * v2y
        val det = v1x * v2y - v1y * v2x
        return Math.toDegrees(atan2(det, dot))
    }
}
