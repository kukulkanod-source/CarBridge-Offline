package com.example.carbridge.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Only the two documented adbd restart services are used; no shell or system-setting mutation. */
internal object OfflineAdbActivation {
    private const val RECONNECT_TIMEOUT_MS = 15_000L
    private const val DISABLE_TIMEOUT_MS = 8_000L
    private const val REFUSAL_STABILITY_MS = 2_000L
    private val restartIo = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "adb-restart-io").apply { isDaemon = true }
    }

    fun activate(
        requestedPort: Int,
        openService: (String) -> AdbChannel,
        disconnectTransport: () -> Unit,
        reconnectLocal: (Int, Long) -> Boolean,
        checkCancelled: () -> Unit
    ): Int {
        require(requestedPort == 0 || requestedPort in 1024..65535) {
            "Локальный порт должен быть 1024–65535, либо 0 для автоматического выбора"
        }
        val port = if (requestedPort == 0) availableLoopbackPort() else requestedPort
        checkCancelled()
        try {
            readRestartAcknowledgement(openService("tcpip:$port"), "restarting in TCP mode port: $port")
        } catch (error: IOException) {
            checkCancelled()
            throw IOException("Переключение ADB на порт $port не подтверждено; порт мог быть изменён", error)
        }
        checkCancelled()
        // adbd restarts when its tcpip: socket closes. A successful ACK alone is insufficient.
        disconnectTransport()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RECONNECT_TIMEOUT_MS)
        var lastFailure: IOException? = null
        while (true) {
            checkCancelled()
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) break
            try {
                if (reconnectLocal(port, minOf(remaining, 2000L))) {
                    checkCancelled()
                    return port
                }
            } catch (error: SocketTimeoutException) {
                checkCancelled()
                lastFailure = error
            } catch (error: InterruptedIOException) {
                throw error
            } catch (error: IOException) {
                lastFailure = error
            }
            pause()
        }
        throw IOException("Порт $port не подтвердил RSA-подключение за 15 секунд; состояние TCP-режима не подтверждено",
            lastFailure)
    }

    fun deactivate(
        port: Int,
        openService: (String) -> AdbChannel,
        disconnectTransport: () -> Unit,
        checkCancelled: () -> Unit
    ) {
        checkCancelled()
        try {
            readRestartAcknowledgement(openService("usb:"), "restarting in USB mode")
        } catch (error: IOException) {
            checkCancelled()
            throw IOException("ADB не подтвердил отключение TCP-режима; состояние порта $port неизвестно", error)
        }
        checkCancelled()
        disconnectTransport()
        // Refusal must remain stable across adbd's restart gap. Timeouts are not proof of closure.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DISABLE_TIMEOUT_MS)
        var firstRefusal = 0L
        while (System.nanoTime() < deadline) {
            checkCancelled()
            if (isConnectionRefused(port)) {
                val now = System.nanoTime()
                if (firstRefusal == 0L) firstRefusal = now
                if (now - firstRefusal >= TimeUnit.MILLISECONDS.toNanos(REFUSAL_STABILITY_MS)) return
            } else firstRefusal = 0L
            pause()
        }
        throw IOException("Команда USB принята, но закрытие TCP-порта $port не подтверждено")
    }

    internal fun requireAcknowledgement(channel: AdbChannel, expectedLine: String, timeoutMs: Long = 5000L) {
        // The deadline owner never calls close() on its timer thread. Even an unusual stream
        // implementation with a stuck close/read cannot monopolise later restart watchdogs.
        val reader = restartIo.submit<String> {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() < 512) {
                val value = channel.input.read()
                if (value < 0) break
                bytes.write(value)
                if (value == '\n'.code) break
            }
            bytes.toString(Charsets.US_ASCII.name()).trimEnd('\r', '\n')
        }
        try {
            val line = reader.get(timeoutMs, TimeUnit.MILLISECONDS)
            if (line != expectedLine) throw IOException("ADB restart service did not acknowledge the requested mode")
        } catch (error: TimeoutException) {
            reader.cancel(true)
            closeBounded(channel)
            throw SocketTimeoutException("ADB restart ACK timed out")
        } catch (error: InterruptedException) {
            reader.cancel(true)
            closeAsynchronously(channel)
            Thread.currentThread().interrupt()
            throw InterruptedIOException("ADB restart ACK interrupted").apply { initCause(error) }
        } catch (error: ExecutionException) {
            throw IOException("ADB restart ACK could not be read", error.cause ?: error)
        } finally {
            if (!reader.isDone) reader.cancel(true)
        }
    }

    private fun readRestartAcknowledgement(channel: AdbChannel, expectedLine: String) {
        try {
            requireAcknowledgement(channel, expectedLine)
        } catch (error: Throwable) {
            closeBounded(channel)
            throw error
        }
        // AOSP exits adbd when this restart-service socket closes. The peer can disappear
        // before our CLSE reaches it, after it has already acknowledged the requested mode.
        closeBounded(channel)
    }

    private fun closeAsynchronously(channel: AdbChannel): Future<*> =
        restartIo.submit { runCatching { channel.close() } }

    private fun closeBounded(channel: AdbChannel) {
        val closing = closeAsynchronously(channel)
        try { closing.get(1000L, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { closing.cancel(true) }
        catch (_: ExecutionException) { }
        catch (_: InterruptedException) {
            closing.cancel(true)
            Thread.currentThread().interrupt()
        }
    }

    private fun availableLoopbackPort(): Int = ServerSocket().use { server ->
        server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        server.localPort
    }

    private fun isConnectionRefused(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
        false
    } catch (_: ConnectException) {
        true
    } catch (_: IOException) {
        false
    }

    private fun pause() {
        try { Thread.sleep(200) }
        catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw AdbOperationCancelledException()
        }
    }
}
