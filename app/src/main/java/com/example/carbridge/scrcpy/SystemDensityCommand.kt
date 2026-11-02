package com.example.carbridge.scrcpy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Legacy v0.26 phone-density query/restore commands. New density changes target the HU display ID. */
internal object SystemDensityCommand {
    private const val ACK = "CARBRIDGE_SYSTEM_DENSITY_OK"
    private val physicalDensity = Regex("(?m)^Physical density:\\s*(\\d+)\\s*$")
    private val overrideDensity = Regex("(?m)^Override density:\\s*(.*?)\\s*$")

    data class State(val physicalDpi: Int, val overrideDpi: Int?) {
        val effectiveDpi: Int get() = overrideDpi ?: physicalDpi
    }

    fun queryDestination(): String = "shell:wm density && echo $ACK"

    fun restoreDestination(originalOverrideDpi: Int?): String {
        if (originalOverrideDpi != null) require(originalOverrideDpi in 1..1280)
        val command = originalOverrideDpi?.let { "wm density $it" } ?: "wm density reset"
        return "shell:$command && wm density && echo $ACK"
    }

    fun readState(input: InputStream, timeoutMs: Long = 2500L): State {
        require(timeoutMs > 0)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val bytes = ByteArrayOutputStream()
        val line = ByteArrayOutputStream()
        while (System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("System density query cancelled")
            if (input.available() == 0) {
                try { Thread.sleep(15L) }
                catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("System density query interrupted").apply { initCause(error) }
                }
                continue
            }
            val value = input.read()
            if (value < 0) break
            if (bytes.size() >= 4096) throw IOException("Unexpected system density response")
            bytes.write(value)
            if (value != '\n'.code) {
                line.write(value)
                continue
            }
            if (line.toString(Charsets.UTF_8.name()).trimEnd('\r') == ACK) {
                val response = bytes.toString(Charsets.UTF_8.name())
                val physical = physicalDensity.find(response)?.groupValues?.get(1)?.toIntOrNull()
                    ?: throw IOException("Android did not report the physical system density")
                val overrideLine = overrideDensity.find(response)?.groupValues?.get(1)
                val override = overrideLine?.toIntOrNull()
                if (overrideLine != null && overrideLine.isNotBlank() && override == null &&
                    !overrideLine.equals("(none)", true) && !overrideLine.equals("none", true)) {
                    throw IOException("Unexpected system density override: ${overrideLine.take(80)}")
                }
                return State(physical, override)
            }
            line.reset()
        }
        throw SocketTimeoutException("System density acknowledgement timed out")
    }

    fun requireApplied(state: State, expectedOverrideDpi: Int?): State {
        if (state.overrideDpi != expectedOverrideDpi) {
            throw IOException("Android reported system density override ${state.overrideDpi ?: "none"}; " +
                "expected ${expectedOverrideDpi ?: "none"}")
        }
        return state
    }
}
