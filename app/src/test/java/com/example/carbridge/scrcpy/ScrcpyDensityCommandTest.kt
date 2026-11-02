package com.example.carbridge.scrcpy

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

class ScrcpyDensityCommandTest {
    @Test
    fun commandOnlyTargetsValidatedSecondaryDisplay() {
        assertEquals("shell:wm density 120 -d 602 && wm density -d 602 && echo CARBRIDGE_DENSITY_OK",
            ScrcpyDensityCommand.destination(120, 602))
        for ((dpi, display) in listOf(120 to 0, 120 to -1, 79 to 602, 121 to 602, 201 to 602)) {
            assertTrue(runCatching { ScrcpyDensityCommand.destination(dpi, display) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test
    fun confirmationMustContainActualRequestedDensityAndExactAck() {
        for (response in listOf("Physical density: 160\r\nOverride density: 120\r\nCARBRIDGE_DENSITY_OK\r\n",
            "Physical density: 120\nCARBRIDGE_DENSITY_OK\n")) {
            ScrcpyDensityCommand.readConfirmation(ByteArrayInputStream(response.toByteArray()), 120, 1000L) { true }
        }
        val wrong = "Physical density: 160\nOverride density: 200\nCARBRIDGE_DENSITY_OK\n"
        val error = runCatching {
            ScrcpyDensityCommand.readConfirmation(ByteArrayInputStream(wrong.toByteArray()), 120, 1000L) { true }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(error is SocketTimeoutException)
    }

    @Test
    fun stalledOrCancelledQueryNeverCallsBlockingRead() {
        val input = object : InputStream() {
            override fun available() = 0
            override fun read(): Int = error("Blocking read")
        }
        assertTrue(runCatching { ScrcpyDensityCommand.readConfirmation(input, 120, 25L) { true } }.exceptionOrNull()
            is SocketTimeoutException)
        assertTrue(runCatching { ScrcpyDensityCommand.readConfirmation(input, 120, 1000L) { false } }.exceptionOrNull()
            is InterruptedIOException)
    }
}
