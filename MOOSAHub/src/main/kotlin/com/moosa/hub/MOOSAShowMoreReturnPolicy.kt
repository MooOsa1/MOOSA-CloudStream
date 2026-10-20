package com.moosa.hub

/**
 * Home shelves can be rebound or reordered when returning from movie details.
 * A RecyclerView adapter index is only a *hint*, never proof of category identity.
 */
internal object MOOSAShowMoreReturnPolicy {
    fun sameCategory(expectedName: String, visibleName: String?): Boolean =
        expectedName.isNotBlank() && visibleName?.trim() == expectedName.trim()

    /**
     * Try the previously visible row first, then nearby rows, then the rest.
     * Never infer identity from row index alone.
     */
    fun candidatePositions(previous: Int, itemCount: Int): List<Int> {
        if (itemCount <= 0) return emptyList()
        val all = (0 until itemCount).toList()
        return if (previous in 0 until itemCount) {
            all.sortedWith(compareBy<Int> { kotlin.math.abs(it - previous) }.thenBy { it })
        } else all
    }
}
