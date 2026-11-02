package com.example.carbridge.scrcpy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

internal object ScrcpyWakeupCommand {
    // Explicitly target the phone. A scrcpy key event would target the virtual display.
    // WAKEUP is idempotent; POWER would toggle and could put it back to sleep.
    const val DESTINATION = "shell:input -d 0 keyevent 224 && echo CARBRIDGE_WAKEUP_OK"
    private const val ACK = "CARBRIDGE_WAKEUP_OK"

    /**
     * LibraryChannel.available() inspects its received ADB queue. Reading only
     * queued bytes avoids an unbounded InputStream.read()/EOF wait on this short
     * shell command. This runs on the control worker, never the main thread.
     */
    fun awaitAcknowledgement(input: InputStream, timeoutMs: Long, isCurrent: () -> Boolean) {
        require(timeoutMs > 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val line = ByteArrayOutputStream()
        while (System.nanoTime() < deadline) {
            if (!isCurrent() || Thread.currentThread().isInterrupted) {
                throw InterruptedIOException("Screen sleep recovery cancelled")
            }
            if (input.available() == 0) {
                try { Thread.sleep(15L) }
                catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Screen sleep recovery interrupted").apply { initCause(error) }
                }
                continue
            }
            val value = input.read()
            if (value == -1) break
            if (value == '\n'.code) {
                if (line.toString(Charsets.US_ASCII.name()).trimEnd('\r') == ACK) return
                throw IOException("Phone wake command was not acknowledged")
            }
            if (line.size() >= 128) throw IOException("Invalid phone wake command acknowledgement")
            line.write(value)
        }
        throw SocketTimeoutException("Phone wake command acknowledgement timed out")
    }
}
