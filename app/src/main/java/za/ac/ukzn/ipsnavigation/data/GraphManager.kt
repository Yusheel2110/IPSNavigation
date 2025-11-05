package za.ac.ukzn.ipsnavigation.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import za.ac.ukzn.ipsnavigation.models.*

/**
 * Handles loading and accessing the indoor graph.
 * Reads /assets/graph.json → converts to Graph object.
 */
class GraphManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: GraphManager? = null

        fun getInstance(context: Context): GraphManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GraphManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val gson = Gson()
    private lateinit var graph: Graph

    /**
     * Load graph.json from assets and parse it into data classes.
     */
    fun loadGraphFromAssets() {
        val json = context.assets.open("graph.json")
            .bufferedReader().use { it.readText() }

        // Use Gson to parse
        val graphType = object : TypeToken<Graph>() {}.type
        graph = gson.fromJson(json, graphType)
    }

    fun isLoaded(): Boolean = ::graph.isInitialized

    fun getGraph(): Graph {
        if (!::graph.isInitialized) throw IllegalStateException("Graph not loaded. Call loadGraphFromAssets() first.")
        return graph
    }

    fun getNodeById(id: String): Node? {
        return graph.nodes.find { it.id == id }
    }

    fun getNodeByLabel(label: String): Node? {
        return graph.nodes.firstOrNull { it.label.equals(label, ignoreCase = true) }
    }

    fun getAllNodes(): List<Node> {
        return graph.nodes
    }

    fun getEdges(): List<Edge> = graph.edges

    fun getNeighbors(nodeId: String): List<Pair<Node, Double>> {
        val edges = graph.edges.filter { it.u == nodeId || it.v == nodeId }
        return edges.mapNotNull { e ->
            val neighborId = if (e.u == nodeId) e.v else e.u
            getNodeById(neighborId)?.let { node -> node to e.w }
        }
    }
    fun findNearestNode(x: Double, y: Double): Node? {
        return graph.nodes.minByOrNull { node ->
            val dx = node.x_m - x
            val dy = node.y_m - y
            dx * dx + dy * dy
        }
    }



    fun getMetadata(): Metadata = graph.metadata
}


