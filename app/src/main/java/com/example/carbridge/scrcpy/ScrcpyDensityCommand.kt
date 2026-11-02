package com.example.carbridge.scrcpy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Only a positive separate display id may be passed to wm; never alter display 0. */
internal object ScrcpyDensityCommand {
    private const val ACK = "CARBRIDGE_DENSITY_OK"
    fun destination(dpi: Int, displayId: Int): String {
        require(dpi in 80..300 && dpi % 10 == 0) { "Недопустимый масштаб дисплея" }
        require(displayId > 0) { "Разрешено менять масштаб только отдельного дисплея ГУ" }
        return "shell:wm density $dpi -d $displayId && wm density -d $displayId && echo $ACK"
    }

    fun readConfirmation(input: InputStream, dpi: Int, timeoutMs: Long, isCurrent: () -> Boolean): String {
        require(timeoutMs > 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val bytes = ByteArrayOutputStream()
        val line = ByteArrayOutputStream()
        while (System.nanoTime() < deadline) {
            if (!isCurrent() || Thread.currentThread().isInterrupted) throw InterruptedIOException("Density change cancelled")
            if (input.available() == 0) {
                try { Thread.sleep(15L) }
                catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Density change interrupted").apply { initCause(error) }
                }
                continue
            }
            val value = input.read()
            if (value < 0) break
            if (bytes.size() >= 4096) throw IOException("Unexpected wm density response")
            bytes.write(value)
            if (value == '\n'.code) {
                if (line.toString(Charsets.UTF_8.name()).trimEnd('\r') == ACK) {
                    val result = bytes.toString(Charsets.UTF_8.name())
                    val override = Regex("(?m)^Override density:\\s*(\\d+)\\s*$").find(result)?.groupValues?.get(1)?.toIntOrNull()
                    val physical = Regex("(?m)^Physical density:\\s*(\\d+)\\s*$").find(result)?.groupValues?.get(1)?.toIntOrNull()
                    if ((override ?: physical) != dpi) throw IOException("Android did not confirm density $dpi: ${result.take(256)}")
                    return result
                }
                line.reset()
            } else line.write(value)
        }
        throw SocketTimeoutException("wm density acknowledgement timed out")
    }
}
