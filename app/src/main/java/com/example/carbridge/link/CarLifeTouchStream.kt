package com.example.carbridge.link

/** One gesture per contact lifetime, including receivers which repeat their original DOWN.
 * Inspired by HSD v0.8.1 ProjectionTouchStream (GPL-3.0), adapted to CarLife's implicit IDs.
 * Confined to the CarLife reader thread; other transports retain their existing input path.
 */
internal class CarLifeTouchStream {
    private var contact: HuLink.TouchEvent? = null
    private var firstDown: Pair<Int, Int>? = null
    private var secondary = false
    var repeatedDowns = 0L
        private set

    fun accept(event: HuLink.TouchEvent): HuLink.TouchEvent? {
        val previous = contact
        when (event.action) {
            0 -> {
                if (previous != null) {
                    repeatedDowns++
                    // Do not rewind a drag to its initial coordinates, or drop a second finger.
                    if (secondary || (event.x to event.y) == firstDown ||
                        (event.x == previous.x && event.y == previous.y)) return null
                    return event.copy(action = 2, actionIndex = 0).also { contact = it }
                }
                firstDown = event.x to event.y
                secondary = false
            }
            2 -> {
                if (previous == null) return null
                if (event.pointerX != null && event.pointerY != null &&
                    (event.pointerX != 0 || event.pointerY != 0)) secondary = true
            }
            5 -> {
                if (event.actionIndex !in 0..1 || event.pointerX == null || event.pointerY == null) return null
                if (secondary) return event.copy(action = 2, actionIndex = 0).also { contact = it }
                // ScrcpySource already recovers a missing primary DOWN on explicit POINTER_DOWN.
                secondary = true
                if (firstDown == null) firstDown = event.x to event.y
            }
            6 -> {
                if (previous == null || !secondary || event.actionIndex !in 0..1) return null
                secondary = false
                contact = if (event.actionIndex == 0 && event.pointerX != null && event.pointerY != null)
                    event.copy(x = event.pointerX, y = event.pointerY, pointerX = null, pointerY = null)
                else event.copy(pointerX = null, pointerY = null)
                firstDown = null // The remaining finger may now have a different implicit ID.
                return event
            }
            1, 3 -> {
                contact = null
                firstDown = null
                secondary = false
                return event.takeIf { previous != null }
            }
            else -> return null
        }
        contact = event
        return event
    }
}
