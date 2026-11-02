package com.example.carbridge.scrcpy

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PhysicalScreenPowerStateTest {
    @Test
    fun recoveryRequiresVideoAndExplicitPanelChoice() {
        val state = PhysicalScreenPowerState()
        assertNull(state.beginRecovery(hasVideo = true))
        state.request(on = true)
        assertNull(state.beginRecovery(hasVideo = false))
        val recovery = state.beginRecovery(hasVideo = true)!!
        assertTrue(recovery.on)
        assertTrue(state.isCurrent(recovery))
        assertFalse(state.finishRecovery(recovery))
        // A second real sleep after the phone was used can be handled again.
        assertNotEquals(recovery, state.beginRecovery(hasVideo = true))
    }

    @Test
    fun duplicateBroadcastsCoalesceWithoutAutomaticRetries() {
        val state = PhysicalScreenPowerState()
        state.request(on = false)
        val first = state.beginRecovery(hasVideo = true)!!
        repeat(5) { assertNull(state.beginRecovery(hasVideo = true)) }
        assertFalse(state.finishRecovery(first))

        val second = state.beginRecovery(hasVideo = true)!!
        repeat(5) { assertNull(state.beginRecovery(hasVideo = true)) }
        assertFalse(state.finishRecovery(second))
        val retry = state.beginRecovery(hasVideo = true)!!
        assertNotEquals(second, retry)
        assertFalse(state.finishRecovery(retry))
    }

    @Test
    fun userOnAndStopInvalidateQueuedOffAndRecovery() {
        val state = PhysicalScreenPowerState()
        state.request(on = false)
        val oldOff = state.takePending()!!
        val recovery = state.beginRecovery(hasVideo = true)!!
        state.beginRecovery(hasVideo = true)
        state.request(on = true)
        val userOn = state.takePending()!!
        assertTrue(userOn.on)
        assertTrue(state.isCurrent(userOn))
        assertFalse(state.isCurrent(oldOff))
        assertFalse(state.isCurrent(recovery))
        assertFalse(state.finishRecovery(recovery))
        val visibleRecovery = state.beginRecovery(hasVideo = true)!!
        assertTrue(visibleRecovery.on)
        assertTrue(state.isCurrent(visibleRecovery))

        state.request(on = false)
        val freshOff = state.takePending()!!
        val freshRecovery = state.beginRecovery(hasVideo = true)!!
        assertTrue(state.isCurrent(freshOff))
        assertTrue(state.isCurrent(freshRecovery))
        state.close()
        assertFalse(state.isCurrent(freshOff))
        assertFalse(state.isCurrent(freshRecovery))
        assertFalse(state.finishRecovery(freshRecovery))
        state.request(on = false)
        assertNull(state.takePending())
        assertNull(state.beginRecovery(hasVideo = true))
    }

    @Test
    fun newerOffRequestCannotBeFinishedByAnOldRecovery() {
        val state = PhysicalScreenPowerState()
        state.request(on = false)
        val first = state.beginRecovery(hasVideo = true)!!
        state.request(on = true)
        state.request(on = false)
        val second = state.beginRecovery(hasVideo = true)!!
        assertFalse(state.finishRecovery(first))
        assertTrue(state.isCurrent(second))
        assertFalse(state.isCurrent(first))
    }

    @Test
    fun wakeCommandAcknowledgementIsExactAndDoesNotWaitForShellEof() {
        // An EOF read after ACK would hang for a persistent shell. Only its
        // queued line should be consumed, with no read past the newline.
        val bytes = "CARBRIDGE_WAKEUP_OK\r\n".toByteArray()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(): Int {
                check(available() > 0) { "Read past shell ACK" }
                return super.read()
            }
        }
        ScrcpyWakeupCommand.awaitAcknowledgement(input, 1000L) { true }
        assertEquals(0, input.available())
        for (invalid in listOf("Error: permission denied\n", "different ACK\n", "x".repeat(129))) {
            val error = runCatching {
                ScrcpyWakeupCommand.awaitAcknowledgement(ByteArrayInputStream(invalid.toByteArray()), 1000L) { true }
            }.exceptionOrNull()
            assertTrue(error is IOException)
            assertFalse(error is SocketTimeoutException)
        }
        assertEquals("shell:input -d 0 keyevent 224 && echo CARBRIDGE_WAKEUP_OK", ScrcpyWakeupCommand.DESTINATION)
    }

    @Test
    fun emptyAdbQueueTimesOutWithoutCallingBlockingRead() {
        var reads = 0
        val empty = object : InputStream() {
            override fun available(): Int = 0
            override fun read(): Int { reads++; error("Would block indefinitely") }
        }
        val started = System.nanoTime()
        val error = runCatching {
            ScrcpyWakeupCommand.awaitAcknowledgement(empty, 25L) { true }
        }.exceptionOrNull()
        assertTrue(error is SocketTimeoutException)
        assertEquals(0, reads)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000L)
    }

    @Test
    fun pendingWakeReadCanBeCancelledWithoutWaitingForTimeout() {
        val current = AtomicBoolean(true)
        val empty = object : InputStream() {
            override fun available(): Int { current.set(false); return 0 }
            override fun read(): Int = error("Cancelled read must not block")
        }
        val started = System.nanoTime()
        val error = runCatching {
            ScrcpyWakeupCommand.awaitAcknowledgement(empty, 10_000L) { current.get() }
        }.exceptionOrNull()
        assertTrue(error is InterruptedIOException)
        assertFalse(error is SocketTimeoutException)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000L)
    }

    @Test
    fun interruptedWakeWaitKeepsInterruptFlag() {
        Thread.currentThread().interrupt()
        try {
            val error = runCatching {
                ScrcpyWakeupCommand.awaitAcknowledgement(ByteArrayInputStream(ByteArray(0)), 1000L) { true }
            }.exceptionOrNull()
            assertTrue(error is InterruptedIOException)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
