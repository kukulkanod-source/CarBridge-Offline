package com.example.carbridge

import kotlin.math.roundToInt

internal object HuDockDimensions {
    const val HEIGHT_DP = 39 // Previously 58: one third less height.
    fun musicWidth(displayWidth: Int, resizeForMusic: Boolean): Int =
        (displayWidth * (if (resizeForMusic) 0.30f else 0.34f)).roundToInt()
            .coerceIn(1, displayWidth.coerceAtLeast(1))
}
