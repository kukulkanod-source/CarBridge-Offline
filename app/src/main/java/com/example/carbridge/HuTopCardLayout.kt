package com.example.carbridge

import kotlin.math.roundToInt

/** Both top overlays reserve disjoint rectangles, even when the map card is hidden. */
internal class HuTopCardLayout(width: Int, private val density: Float, resizeForMusic: Boolean = true) {
    private fun px(value: Int) = (value * density).roundToInt()
    val inset = px(5)
    val gap = px(8)
    private val available = (width - inset * 2 - gap).coerceAtLeast(2)
    val navigationWidth = HuDockDimensions.musicWidth(width, resizeForMusic).coerceIn(1, available - 1)
    // Leave the unrelated right-hand banner at its previous width, with the
    // free centre space uncovered after narrowing the navigation scene.
    val notificationWidth = minOf(available - navigationWidth, (available * 0.52f).roundToInt().coerceAtLeast(1))
    val height = px(100)
    val navigationHeight = px(320)
    val radius = px(10).toFloat()
}
