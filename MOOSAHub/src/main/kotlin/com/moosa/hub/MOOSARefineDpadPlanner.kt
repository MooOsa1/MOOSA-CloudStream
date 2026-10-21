package com.moosa.hub

import kotlin.math.abs

/**
 * Pure DPAD navigation model for the top Refine panel.
 * Each nested list describes one visible-semantic row's horizontal
 * button centers, including ScrollView rows outside its viewport.
 * Never jumps over an intermediate row or wraps into movie posters.
 */
internal object MOOSARefineDpadPlanner {
    const val UP = 19
    const val DOWN = 20
    const val LEFT = 21
    const val RIGHT = 22

    fun next(
        rows: List<List<Int>>,
        currentRow: Int,
        currentColumn: Int,
        key: Int,
        selectedTab: Int
    ): Pair<Int, Int>? {
        val current = rows.getOrNull(currentRow) ?: return null
        val x = current.getOrNull(currentColumn) ?: return null
        when (key) {
            LEFT -> return if (currentColumn > 0)
                currentRow to (currentColumn - 1) else null
            RIGHT -> return if (currentColumn < current.lastIndex)
                currentRow to (currentColumn + 1) else null
        }
        val targetRow = when (key) {
            UP -> (currentRow - 1).takeIf { it >= 0 }
            DOWN -> (currentRow + 1).takeIf { it < rows.size }
            else -> null
        } ?: return null
        val target = rows[targetRow]
        if (target.isEmpty()) return null
        val desired = when {
            currentRow == 0 && key == DOWN -> selectedTab
            currentRow == 2 && key == UP -> selectedTab
            else -> target.indices.minByOrNull { abs(target[it] - x) } ?: 0
        }
        return targetRow to desired.coerceIn(target.indices)
    }
}
