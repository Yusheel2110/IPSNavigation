package za.ac.ukzn.ipsnavigation.data

import android.content.Context
import za.ac.ukzn.ipsnavigation.models.Node
import kotlin.math.hypot

/**
 * Implements A* pathfinding using the loaded Graph from GraphManager.
 */
class PathFinder(private val context: Context) {

    private val graphManager = GraphManager.getInstance(context)

    /**
     * Euclidean distance heuristic (straight-line distance between two nodes).
     */
    private fun heuristic(a: Node, b: Node): Double {
        return hypot(a.x_m - b.x_m, a.y_m - b.y_m)
    }

    /**
     * Compute shortest path between startLabel and goalLabel.
     * Returns the ordered list of Node labels forming the route.
     */
    fun findPath(startLabel: String, goalLabel: String): List<Node> {
        val graph = graphManager.getGraph()
        val start = graphManager.getNodeByLabel(startLabel)
            ?: throw IllegalArgumentException("Start node not found: $startLabel")
        val goal = graphManager.getNodeByLabel(goalLabel)
            ?: throw IllegalArgumentException("Goal node not found: $goalLabel")

        // A* data structures
        val openSet = mutableSetOf(start.id)
        val cameFrom = mutableMapOf<String, String?>()
        val gScore = mutableMapOf<String, Double>().withDefault { Double.POSITIVE_INFINITY }
        val fScore = mutableMapOf<String, Double>().withDefault { Double.POSITIVE_INFINITY }

        gScore[start.id] = 0.0
        fScore[start.id] = heuristic(start, goal)

        while (openSet.isNotEmpty()) {
            // Node with lowest F-score
            val currentId = openSet.minByOrNull { fScore.getValue(it) } ?: break
            val currentNode = graph.nodes.find { it.id == currentId } ?: break

            if (currentId == goal.id) {
                return reconstructPath(cameFrom, currentId)
                    .mapNotNull { id -> graph.nodes.find { it.id == id } }
            }

            openSet.remove(currentId)

            for ((neighbor, distance) in graphManager.getNeighbors(nodeId = currentId)) {
                val tentativeG = gScore.getValue(currentId) + distance
                if (tentativeG < gScore.getValue(neighbor.id)) {
                    cameFrom[neighbor.id] = currentId
                    gScore[neighbor.id] = tentativeG
                    fScore[neighbor.id] = tentativeG + heuristic(neighbor, goal)
                    openSet.add(neighbor.id)
                }
            }
        }


        return emptyList() // no path found
    }

    /**
     * Backtrack from goal to start using cameFrom map.
     */
    private fun reconstructPath(cameFrom: Map<String, String?>, currentId: String): List<String> {
        val totalPath = mutableListOf(currentId)
        var cur = currentId
        while (cameFrom.containsKey(cur)) {
            val prev = cameFrom[cur]
            if (prev != null) {
                totalPath.add(prev)
                cur = prev
            } else break
        }
        return totalPath.reversed()
    }
}
