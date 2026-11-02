package com.example.carbridge.scrcpy

/** A short, symmetric two-finger gesture in encoded-video pixels; never changes density. */
class PinchGesture private constructor(
    val width: Int,
    val height: Int,
    val steps: List<Step>,
) {
    data class Touch(val action: Int, val pointerId: Long, val x: Int, val y: Int)
    data class Step(val atMs: Long, val touches: List<Touch>)

    /** Track even a partially written DOWN; remove a finger only after its UP was written. */
    class Pointers {
        private val active = linkedMapOf<Long, Touch>()

        fun beforeWrite(touch: Touch) {
            if (touch.action == 0 || touch.action == 2) active[touch.pointerId] = touch
        }

        fun afterWrite(touch: Touch) {
            if (touch.action == 1) active.remove(touch.pointerId)
        }

        // scrcpy 5.0.1 does not remove pointer registrations on ACTION_CANCEL.
        fun releases(): List<Touch> = active.values.toList().asReversed().map { it.copy(action = 1) }
    }

    companion object {
        const val FIRST_FINGER = 0x4342524944474501L
        const val SECOND_FINGER = 0x4342524944474502L
        const val DURATION_MS = 360L
        const val MAX_ELAPSED_MS = 1500L
        private const val MOVE_COUNT = 10

        fun create(width: Int, height: Int, zoomIn: Boolean): PinchGesture {
            require(width in 64..65535 && height in 64..65535) { "Display is too small for a pinch" }
            val centerX = (width - 1) / 2
            // Keep both fingers above the independent navigation strip at the bottom.
            val y = ((height - 1) * 2 / 5).coerceAtLeast(1)
            val near = maxOf(8, width * 12 / 100)
            val far = maxOf(near + 2, width * 28 / 100).coerceAtMost(centerX - 2)
            val start = if (zoomIn) near else far
            val end = if (zoomIn) far else near
            fun pair(action: Int, distance: Int): List<Touch> = listOf(
                Touch(action, FIRST_FINGER, centerX - distance, y),
                Touch(action, SECOND_FINGER, centerX + distance, y),
            )
            val steps = mutableListOf(Step(0L, pair(0, start)))
            for (index in 1..MOVE_COUNT) {
                val distance = start + (end - start) * index / MOVE_COUNT
                steps += Step(index * 30L, pair(2, distance))
            }
            steps += Step(DURATION_MS, pair(1, end).asReversed())
            return PinchGesture(width, height, steps)
        }
    }
}
