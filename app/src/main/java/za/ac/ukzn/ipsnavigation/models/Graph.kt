package za.ac.ukzn.ipsnavigation.models

data class Graph(
    val nodes: List<Node>,          // This should be a List to match the JSON array
    val edges: List<Edge>,
    val metadata: Metadata
)

data class Metadata(
    val building: String? = null,
    val floor: Int? = null,
    val units: String? = null,
    val scale: Scale? = null,
    val image_size_px: ImageSize? = null,
    val origin: String? = null,
    val north_angle_deg: Double? = null,
    val alignment_offsets_m: Offsets? = null
)

data class Scale(val px_per_meter: Double? = null)
data class ImageSize(val width: Int? = null, val height: Int? = null)
data class Offsets(val x: Double? = null, val y: Double? = null)
