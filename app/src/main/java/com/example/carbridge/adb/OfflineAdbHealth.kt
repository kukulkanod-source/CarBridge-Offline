package com.example.carbridge.adb

import android.content.Context
import android.os.Looper
import android.provider.Settings
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Reconnects to a previously activated port; never activates, disables or reconfigures adbd. */
object OfflineAdbHealth {
    enum class Status {
        CONNECTED, USB_DEBUGGING_DISABLED, PORT_CLOSED, AUTHENTICATION_REQUIRED, TIMEOUT, UNAVAILABLE, CANCELLED
    }

    data class Result(val status: Status, val summary: String, val details: String) {
        val connected: Boolean get() = status == Status.CONNECTED
    }

    /** Worker-only. All attempts share one budget, including the final TCP diagnostic. */
    fun checkLocal(
        context: Context,
        port: Int,
        budgetMs: Long = 12_000L,
        cancelled: () -> Boolean = { false },
        report: (String) -> Unit = {},
    ): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Проверяйте ADB в рабочем потоке" }
        val app = context.applicationContext
        return check(port, budgetMs, Operations(
            nowMs = { TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) },
            pause = { Thread.sleep(it) },
            usbDebugging = {
                runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.ADB_ENABLED, -1) }
                    .getOrNull()?.takeIf { it >= 0 }?.let { it != 0 }
            },
            probe = { AdbSession.probeConnected("127.0.0.1", port, AdbConnectionMode.LOCAL_TCP, it) },
            connect = { AdbSession.connectLocal(app, port, it) },
            inspectPort = { inspectPort(port, it) },
        ), cancelled, report)
    }

    internal enum class PortState { OPEN, CLOSED, TIMED_OUT, UNKNOWN }

    internal class Operations(
        val nowMs: () -> Long,
        val pause: (Long) -> Unit,
        val usbDebugging: () -> Boolean?,
        val probe: (Long) -> Boolean,
        val connect: (Long) -> Boolean,
        val inspectPort: (Long) -> PortState,
    )

    internal fun check(
        port: Int,
        budgetMs: Long,
        operations: Operations,
        cancelled: () -> Boolean = { false },
        report: (String) -> Unit = {},
    ): Result {
        require(port in 1..65535) { "Не сохранён локальный порт ADB" }
        require(budgetMs in 1..12_000L) { "Бюджет проверки ADB должен быть от 1 до 12000 мс" }
        val started = operations.nowMs()
        val deadline = started + budgetMs
        // Reserve a short, read-only TCP probe after the authenticated reconnect attempts.
        val retryDeadline = deadline - minOf(500L, budgetMs / 4)
        // AdbSession may spend up to 500 ms joining an aborted transport after its
        // network deadline. Leave that cleanup time inside this overall budget.
        val cleanupReserve = 600L
        var attempts = 0
        var usbDebugging: Boolean? = null
        var portState = PortState.UNKNOWN
        var lastFailure = "none"

        fun remaining(until: Long = deadline) = (until - operations.nowMs()).coerceAtLeast(0L)
        fun callBudget(maximum: Long) = minOf(maximum, (remaining(retryDeadline) - cleanupReserve).coerceAtLeast(0L))
        fun stopped() = Thread.currentThread().isInterrupted || cancelled()
        fun emit(message: String) { runCatching { report(message) } }
        fun result(status: Status): Result {
            val endpoint = "127.0.0.1:$port"
            val summary = when (status) {
                Status.CONNECTED -> "Локальный ADB отвечает: $endpoint. Повторная активация не нужна; Wi-Fi для подключения к ГУ не требуется." +
                    if (usbDebugging == false) " Включите и оставьте включённой «Отладку по USB», чтобы ADB мог работать без Wi-Fi." else ""
                Status.USB_DEBUGGING_DISABLED -> "«Отладка по USB» выключена, локальный ADB не ответил. Включите её и повторите проверку. Сохранённый порт $port оставлен."
                Status.PORT_CLOSED -> "Локальный порт ADB $endpoint не принимает соединения после ожидания. Настройки и порт сохранены. " +
                    "Проверьте «Отладку по USB». Если повторная проверка не поможет, восстановите офлайн-режим через TLS; на этой прошивке порт мог закрыться при переключении USB."
                Status.AUTHENTICATION_REQUIRED -> "ADB доступен, но не принял сохранённый ключ. Проверьте разрешение отладки на разблокированном телефоне; при необходимости восстановите сопряжение через TLS. Порт $port сохранён."
                Status.TIMEOUT -> "Проверка локального ADB $endpoint не завершила авторизованное подключение вовремя. Порт сохранён. Разблокируйте телефон и повторите проверку; Wi-Fi для самой проверки не нужен."
                Status.UNAVAILABLE -> "Локальный ADB $endpoint не подтвердил авторизованное подключение. Порт сохранён. Проверьте отладку по USB и повторите проверку; причина записана в журнал."
                Status.CANCELLED -> "Проверка локального ADB отменена: началась другая сессия или операция."
            }
            val details = "local_adb status=$status endpoint=$endpoint attempts=$attempts " +
                "elapsed_ms=${(operations.nowMs() - started).coerceAtLeast(0L)} " +
                "usb_debugging=${usbDebugging ?: "unknown"} tcp=$portState last_failure=$lastFailure"
            emit(details)
            return Result(status, summary, details)
        }

        try {
            if (stopped()) return result(Status.CANCELLED)
            usbDebugging = operations.usbDebugging()
            emit("local_adb begin endpoint=127.0.0.1:$port usb_debugging=${usbDebugging ?: "unknown"} budget_ms=$budgetMs")
            if (stopped()) return result(Status.CANCELLED)
            val initialProbeBudget = callBudget(1000L)
            if (initialProbeBudget > 0) {
                try {
                    if (operations.probe(initialProbeBudget)) {
                        return result(if (stopped()) Status.CANCELLED else Status.CONNECTED)
                    }
                } catch (error: Exception) {
                    lastFailure = classify(error)
                    if (lastFailure == "cancelled") return result(Status.CANCELLED)
                }
            }
            while (!stopped() && callBudget(2500L) > 0) {
                attempts++
                emit("local_adb reconnect attempt=$attempts remaining_ms=${remaining(retryDeadline)}")
                try {
                    if (operations.connect(callBudget(2500L).coerceAtLeast(1L))) {
                        if (stopped()) return result(Status.CANCELLED)
                        val roundTripBudget = callBudget(1000L)
                        if (roundTripBudget > 0 && operations.probe(roundTripBudget)) {
                            return result(if (stopped()) Status.CANCELLED else Status.CONNECTED)
                        }
                        lastFailure = "round_trip_failed"
                    } else lastFailure = "timeout"
                } catch (error: Exception) {
                    lastFailure = classify(error)
                    emit("local_adb attempt=$attempts failure=$lastFailure exception=${error.javaClass.simpleName}")
                    if (lastFailure == "cancelled") return result(Status.CANCELLED)
                    if (lastFailure == "authentication") return result(Status.AUTHENTICATION_REQUIRED)
                }
                if (stopped()) return result(Status.CANCELLED)
                // A disabled USB-debugging switch needs user action, not twelve seconds of retries.
                if (usbDebugging == false) break
                val delay = minOf(350L, remaining(retryDeadline))
                if (delay > 0) operations.pause(delay)
            }
            if (stopped()) return result(Status.CANCELLED)
            if (remaining() > 0) portState = operations.inspectPort(minOf(500L, remaining()))
            if (stopped()) return result(Status.CANCELLED)
            // Re-read because an OEM may have changed the debugging setting during USB teardown.
            usbDebugging = operations.usbDebugging()
            return result(when {
                usbDebugging == false -> Status.USB_DEBUGGING_DISABLED
                portState == PortState.CLOSED -> Status.PORT_CLOSED
                portState == PortState.TIMED_OUT || lastFailure == "timeout" -> Status.TIMEOUT
                else -> Status.UNAVAILABLE
            })
        } catch (error: Exception) {
            lastFailure = classify(error)
            return result(if (stopped() || lastFailure == "cancelled") Status.CANCELLED else Status.UNAVAILABLE)
        }
    }

    /** Class names only enter diagnostics: no handshake bytes, keys or pairing codes. */
    internal fun classify(error: Throwable): String {
        var cause: Throwable? = error
        val seen = HashSet<Throwable>()
        while (cause != null && seen.add(cause)) {
            if (cause is AdbAuthenticationFailedException) return "authentication"
            if (cause is SocketTimeoutException) return "timeout"
            if (cause is InterruptedException || cause is InterruptedIOException) return "cancelled"
            if (cause is ConnectException || cause.message?.contains("ECONNREFUSED", ignoreCase = true) == true) return "refused"
            cause = cause.cause
        }
        return "io_or_transport"
    }

    /** The diagnostic socket is always closed, and is never installed as our ADB connection. */
    internal fun inspectPort(port: Int, timeoutMs: Long): PortState = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs.coerceIn(1L, 500L).toInt())
        }
        PortState.OPEN
    } catch (_: ConnectException) {
        PortState.CLOSED
    } catch (_: SocketTimeoutException) {
        PortState.TIMED_OUT
    } catch (error: IOException) {
        if (classify(error) == "refused") PortState.CLOSED else PortState.UNKNOWN
    }
}
