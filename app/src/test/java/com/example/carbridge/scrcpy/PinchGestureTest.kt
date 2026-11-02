package com.example.carbridge.scrcpy

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PinchGestureTest {
    private val sizes = listOf(64 to 64, 800 to 480, 1280 to 720,
        65535 to 65535, 64 to 65535, 65535 to 64)

    @Test
    fun zoomInSpreadsFingersAndZoomOutBringsThemTogetherWithoutMovingCenter() {
        for ((width, height) in sizes) {
            for (zoomIn in listOf(true, false)) {
                val gesture = PinchGesture.create(width, height, zoomIn)
                assertEquals(width, gesture.width)
                assertEquals(height, gesture.height)
                val pairs = gesture.steps.map { step -> step.touches.sortedBy { it.x } }
                val initial = pairs.first()
                val centerSum = initial.sumOf { it.x }
                val initialY = initial.first().y
                val distances = pairs.map { pair ->
                    assertEquals(2, pair.size)
                    assertTrue(pair[0].x < pair[1].x)
                    assertEquals(centerSum, pair.sumOf { it.x })
                    for (touch in pair) {
                        assertTrue("x must stay in the encoded frame", touch.x in 0 until width)
                        assertTrue("y must stay in the encoded frame", touch.y in 0 until height)
                        assertEquals("pinch must not pan the map vertically", initialY, touch.y)
                    }
                    pair[1].x - pair[0].x
                }
                for ((before, after) in distances.zipWithNext()) {
                    assertTrue("separation must be monotonic", if (zoomIn) after >= before else after <= before)
                }
                assertTrue("gesture must actually change separation",
                    if (zoomIn) distances.last() > distances.first() else distances.last() < distances.first())
                assertTrue("gesture center must be near the display center",
                    kotlin.math.abs(centerSum - (width - 1)) <= 1)
                assertTrue("map pinch must leave the bottom navigation area alone", initialY < height / 2)
            }
        }
    }

    @Test
    fun oppositeZoomDirectionsUseTheSameEndpointsInReverse() {
        for ((width, height) in sizes) {
            val zoomIn = PinchGesture.create(width, height, true)
            val zoomOut = PinchGesture.create(width, height, false)
            fun positions(step: PinchGesture.Step) = step.touches.associate { it.pointerId to (it.x to it.y) }
            assertEquals(positions(zoomIn.steps.first()), positions(zoomOut.steps.last()))
            assertEquals(positions(zoomIn.steps.last()), positions(zoomOut.steps.first()))
        }
    }

    @Test
    fun gestureHasTwoUniqueFingerDownsThenMovesThenReverseOrderedUps() {
        for (zoomIn in listOf(true, false)) {
            val steps = PinchGesture.create(800, 480, zoomIn).steps
            val ids = steps.first().touches.map { it.pointerId }
            assertEquals(2, ids.toSet().size)
            assertFalse("pinch must not collide with scrcpy mouse id", -1L in ids)
            assertFalse("pinch must not collide with ordinary one-finger touch", -2L in ids)
            assertTrue(steps.first().touches.all { it.action == 0 })
            assertTrue("gesture requires motion after both fingers are down", steps.size > 2)
            for (step in steps.subList(1, steps.lastIndex)) {
                assertEquals(ids, step.touches.map { it.pointerId })
                assertTrue(step.touches.all { it.action == 2 })
            }
            assertEquals(ids.reversed(), steps.last().touches.map { it.pointerId })
            assertTrue(steps.last().touches.all { it.action == 1 })
            assertEquals(0L, steps.first().atMs)
            assertEquals(360L, steps.last().atMs)
            assertEquals(PinchGesture.DURATION_MS, steps.last().atMs)
            assertTrue(steps.zipWithNext().all { (before, after) -> after.atMs > before.atMs })
        }
    }

    @Test
    fun failedFirstDownStillRequiresOneUpAndNoCancel() {
        val pointers = PinchGesture.Pointers()
        val firstDown = PinchGesture.create(800, 480, true).steps.first().touches.first()
        pointers.beforeWrite(firstDown)
        assertEquals(listOf(firstDown.copy(action = 1)), pointers.releases())
        assertTrue(pointers.releases().all { it.action == 1 })
    }

    @Test
    fun partialMoveReleasesBothPointersInReverseDownOrderUsingLatestCoordinates() {
        val gesture = PinchGesture.create(1280, 720, false)
        val pointers = PinchGesture.Pointers()
        for (down in gesture.steps.first().touches) {
            pointers.beforeWrite(down)
            pointers.afterWrite(down)
        }
        val movedFirst = gesture.steps[1].touches.first()
        pointers.beforeWrite(movedFirst) // A write may fail after some bytes reached the server.
        val secondDown = gesture.steps.first().touches.last()
        val expected = listOf(secondDown.copy(action = 1), movedFirst.copy(action = 1))
        assertEquals(expected, pointers.releases())
        assertEquals("reading pending releases must not discard them", expected, pointers.releases())
    }

    @Test
    fun failedUpRemainsPendingUntilItsWriteSucceeds() {
        val gesture = PinchGesture.create(800, 480, true)
        val pointers = PinchGesture.Pointers()
        for (step in gesture.steps.dropLast(1)) for (touch in step.touches) {
            pointers.beforeWrite(touch)
            pointers.afterWrite(touch)
        }
        val ups = gesture.steps.last().touches
        pointers.beforeWrite(ups[0])
        assertEquals("an interrupted UP may not have reached the device", ups, pointers.releases())
        pointers.afterWrite(ups[0])
        assertEquals(listOf(ups[1]), pointers.releases())
        pointers.beforeWrite(ups[1])
        assertEquals(listOf(ups[1]), pointers.releases())
        pointers.afterWrite(ups[1])
        assertTrue(pointers.releases().isEmpty())
    }

    @Test
    fun fingerPacketsPreserveIdsAndUseScrcpyTouchLayoutWithoutMouseButtons() {
        for (id in listOf(PinchGesture.FIRST_FINGER, PinchGesture.SECOND_FINGER)) {
            for (action in 0..3) {
                val packet = ScrcpyProtocol.touchPointer(action, id, 321, 123, 800, 480)
                assertEquals(32, packet.size)
                DataInputStream(ByteArrayInputStream(packet)).use { input ->
                    assertEquals("INJECT_TOUCH_EVENT", 2, input.readUnsignedByte())
                    assertEquals(action, input.readUnsignedByte())
                    assertEquals(id, input.readLong())
                    assertEquals(321, input.readInt())
                    assertEquals(123, input.readInt())
                    assertEquals(800, input.readUnsignedShort())
                    assertEquals(480, input.readUnsignedShort())
                    assertEquals(if (action == 1 || action == 3) 0 else 65535, input.readUnsignedShort())
                    assertEquals("actionButton must be absent for fingers", 0, input.readInt())
                    assertEquals("mouse buttons must be absent for fingers", 0, input.readInt())
                    assertEquals(0, input.available())
                }
            }
        }
    }

    @Test
    fun touchCoordinatesClampToFrameBoundsAndDimensionsRemainUnsigned16Bit() {
        for ((width, height) in sizes) {
            for ((x, y) in listOf(Int.MIN_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to Int.MIN_VALUE)) {
                val packet = ScrcpyProtocol.touchPointer(2, PinchGesture.FIRST_FINGER, x, y, width, height)
                DataInputStream(ByteArrayInputStream(packet)).use { input ->
                    assertEquals(10, input.skipBytes(10))
                    assertEquals(if (x < 0) 0 else width - 1, input.readInt())
                    assertEquals(if (y < 0) 0 else height - 1, input.readInt())
                    assertEquals(width, input.readUnsignedShort())
                    assertEquals(height, input.readUnsignedShort())
                }
            }
        }
    }

    @Test
    fun invalidGestureSizesAndMousePointerPacketsAreRejected() {
        for (bad in listOf(Int.MIN_VALUE, -1, 0, 1, 63, 65536, Int.MAX_VALUE)) {
            reject { PinchGesture.create(bad, 480, true) }
            reject { PinchGesture.create(800, bad, false) }
        }
        reject { ScrcpyProtocol.touchPointer(0, -1L, 100, 100, 800, 480) }
        for (badAction in listOf(-1, 4, Int.MAX_VALUE)) {
            reject { ScrcpyProtocol.touchPointer(badAction, PinchGesture.FIRST_FINGER, 100, 100, 800, 480) }
        }
        for (bad in listOf(-1, 0, 65536, Int.MAX_VALUE)) {
            reject { ScrcpyProtocol.touchPointer(0, PinchGesture.FIRST_FINGER, 100, 100, bad, 480) }
            reject { ScrcpyProtocol.touchPointer(0, PinchGesture.FIRST_FINGER, 100, 100, 800, bad) }
        }
    }

    private fun reject(block: () -> Unit) {
        try {
            block()
            fail("Invalid gesture input must be rejected")
        } catch (_: IllegalArgumentException) {
            // Deliberate fail-fast boundary, before any control packet is written.
        }
    }
}
