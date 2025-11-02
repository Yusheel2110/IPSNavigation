package za.ac.ukzn.ipsnavigation.models

data class Node(
    val id: String,
    val label: String?,      // e.g., "1-10", "C7", "Stairs"
    val x_m: Double,         // meters (local floor coords)
    val y_m: Double,         // meters
    val z: Int,              // floor level (always 1 for now)
    val type: String         // "room", "junction", etc.
) {
    val isRoom: Boolean get() = type.equals("room", ignoreCase = true)
    val isJunction: Boolean get() = type.equals("junction", ignoreCase = true)
}
