package za.ac.ukzn.ipsnavigation.models

data class Edge(
    val u: String,           // source node id
    val v: String,           // target node id
    val w: Double,           // weight in meters
    val edge_type: String? = null  // "manual" / "auto" (optional)
)
