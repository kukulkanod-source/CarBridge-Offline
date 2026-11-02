package com.example.carbridge.input

/**
 * Координаты кадра ГУ (outW x outH) -> координаты экрана телефона (srcW x srcH).
 * padX/padY: чёрные поля в кадре, если картинка вписывалась с letterbox.
 */
class TouchMapper(
    private val outW: Int, private val outH: Int,
    private val srcW: Int, private val srcH: Int,
    private val padX: Int = 0, private val padY: Int = 0
) {
    fun map(x: Int, y: Int): Pair<Float, Float> {
        val cw = (outW - 2 * padX).coerceAtLeast(1)
        val ch = (outH - 2 * padY).coerceAtLeast(1)
        val nx = (x - padX).coerceIn(0, cw - 1).toFloat() / cw
        val ny = (y - padY).coerceIn(0, ch - 1).toFloat() / ch
        return nx * srcW to ny * srcH
    }
}
