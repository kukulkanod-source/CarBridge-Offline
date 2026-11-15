package com.example.carbridge.link

import org.junit.Assert.*
import org.junit.Test

class CarLifeTouchStreamTest {
    private fun event(action: Int, x: Int = 10, y: Int = 20, px: Int? = null, py: Int? = null, index: Int = 0) =
        HuLink.TouchEvent(action, index, x, y, px, py)

    @Test fun repeatedInitialDownDoesNotRewindDragAndUpdatedDownBecomesMove() {
        val stream = CarLifeTouchStream()
        assertEquals(0, stream.accept(event(0))?.action)
        assertEquals(2, stream.accept(event(2, 50, 70))?.action)
        assertNull(stream.accept(event(0)))
        assertNull(stream.accept(event(0, 50, 70)))
        val next = stream.accept(event(0, 60, 80))!!
        assertEquals(2, next.action)
        assertEquals(60, next.x)
        assertEquals(3, stream.repeatedDowns)
        assertEquals(1, stream.accept(event(1, 60, 80))?.action)
        assertEquals(0, stream.accept(event(0))?.action)
    }

    @Test fun repeatedDownDoesNotEndPinchAndPrimaryUpKeepsRemainingContact() {
        val stream = CarLifeTouchStream()
        stream.accept(event(0))
        assertEquals(5, stream.accept(event(5, px = 100, py = 120, index = 1))?.action)
        assertNull(stream.accept(event(0)))
        assertEquals(2, stream.accept(event(5, 15, 25, 110, 130, 1))?.action)
        assertEquals(6, stream.accept(event(6, 15, 25, 110, 130, 0))?.action)
        assertEquals(2, stream.accept(event(2, 115, 135))?.action)
        assertEquals(1, stream.accept(event(1, 115, 135))?.action)
        assertNull(stream.accept(event(2, 120, 140)))
    }

    @Test fun cancelAllowsFreshGestureAndPaddingDoesNotCreateSecondFinger() {
        val stream = CarLifeTouchStream()
        assertNull(stream.accept(event(2)))
        stream.accept(event(0))
        stream.accept(event(2, 30, 40, 0, 0))
        assertNull(stream.accept(event(6, 30, 40, 0, 0, 1)))
        assertEquals(3, stream.accept(event(3, -1, -1))?.action)
        assertNull(stream.accept(event(1)))
        assertEquals(0, stream.accept(event(0))?.action)
        assertNull(stream.accept(event(5, px = 100, py = 120, index = 2)))
    }
}
