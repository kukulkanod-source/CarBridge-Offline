package com.example.carbridge.adb

import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OfflineAdbHealthTest {
    @Test fun anExistingAuthenticatedRoundTripDoesNotReconnectOrOpenAnotherSocket() {
        val fake = Fake().apply { alive = true }
        val result = OfflineAdbHealth.check(42305, 12_000, fake.operations())
        assertEquals(OfflineAdbHealth.Status.CONNECTED, result.status)
        assertEquals(0, fake.connections)
        assertEquals(0, fake.portChecks)
    }

    @Test fun temporaryRefusalDuringUsbReconfigurationRecoversWithoutReactivation() {
        val fake = Fake().apply {
            connect = { if (connections < 3) throw ConnectException("ECONNREFUSED") else { alive = true; true } }
        }
        val result = OfflineAdbHealth.check(42305, 12_000, fake.operations())
        assertTrue(result.connected)
        assertEquals(3, fake.connections)
        assertEquals(700, fake.now)
        assertTrue(result.summary.contains("Повторная активация не нужна"))
        assertEquals(0, fake.portChecks)
    }

    @Test fun reconnectsAndTheFinalDiagnosticShareOneDeadline() {
        val budgets = mutableListOf<Long>()
        val fake = Fake().apply {
            connect = { timeout -> budgets += timeout; now += timeout; throw SocketTimeoutException() }
            portState = OfflineAdbHealth.PortState.TIMED_OUT
        }
        val result = OfflineAdbHealth.check(42305, 4000, fake.operations())
        assertEquals(OfflineAdbHealth.Status.TIMEOUT, result.status)
        assertTrue(budgets.size > 1)
        assertTrue(budgets.all { it in 1..2500 })
        assertTrue(fake.now <= 4000)
        assertEquals(1, fake.portChecks)
        assertTrue(fake.lastPortBudget in 1..500)
    }

    @Test fun onlyARefusedFinalTcpProbeReportsThePortClosed() {
        val fake = Fake().apply {
            connect = { throw ConnectException("ECONNREFUSED") }
            portState = OfflineAdbHealth.PortState.CLOSED
        }
        val result = OfflineAdbHealth.check(42305, 3000, fake.operations())
        assertEquals(OfflineAdbHealth.Status.PORT_CLOSED, result.status)
        assertTrue(fake.connections > 1)
        assertTrue(result.summary.contains("Настройки и порт сохранены"))
        assertTrue(result.details.contains("tcp=CLOSED"))
    }

    @Test fun aWrappedAuthenticationFailureIsReportedWithoutRepeatingThePrompt() {
        val fake = Fake().apply {
            connect = { throw IOException("transport failed", AdbAuthenticationFailedException()) }
        }
        val result = OfflineAdbHealth.check(42305, 12_000, fake.operations())
        assertEquals(OfflineAdbHealth.Status.AUTHENTICATION_REQUIRED, result.status)
        assertEquals(1, fake.connections)
        assertEquals(0, fake.portChecks)
        assertEquals("authentication", OfflineAdbHealth.classify(IOException(AdbAuthenticationFailedException())))
    }

    @Test fun anOpenTcpPortAloneIsNeverConsideredAnAuthenticatedConnection() {
        val fake = Fake().apply {
            connect = { false }
            portState = OfflineAdbHealth.PortState.OPEN
        }
        val result = OfflineAdbHealth.check(42305, 1000, fake.operations())
        assertEquals(OfflineAdbHealth.Status.TIMEOUT, result.status)
        assertFalse(result.connected)
        assertTrue(result.details.contains("tcp=OPEN"))
    }

    @Test fun disabledUsbDebuggingHasAnActionableResultWithoutChangingTheSetting() {
        val fake = Fake().apply {
            usb = false
            connect = { throw ConnectException("ECONNREFUSED") }
            portState = OfflineAdbHealth.PortState.CLOSED
        }
        val result = OfflineAdbHealth.check(42305, 12_000, fake.operations())
        assertEquals(OfflineAdbHealth.Status.USB_DEBUGGING_DISABLED, result.status)
        assertEquals(1, fake.connections)
        assertEquals(false, fake.usb)
        assertTrue(result.summary.contains("Сохранённый порт 42305 оставлен"))
    }

    @Test fun aNewSessionCancelsOldReconnectAttemptsBeforeAnotherConnection() {
        val fake = Fake().apply { connect = { throw ConnectException() } }
        val result = OfflineAdbHealth.check(42305, 12_000, fake.operations(), cancelled = { fake.now >= 350 })
        assertEquals(OfflineAdbHealth.Status.CANCELLED, result.status)
        assertEquals(1, fake.connections)
        assertEquals(0, fake.portChecks)
    }

    @Test(timeout = 2000) fun diagnosticTcpSocketIsClosedAfterAnOpenPortWasObserved() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val peerClosed = CountDownLatch(1)
            val peer = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 1000
                    if (socket.getInputStream().read() == -1) peerClosed.countDown()
                }
            }
            peer.start()
            assertEquals(OfflineAdbHealth.PortState.OPEN, OfflineAdbHealth.inspectPort(server.localPort, 500))
            assertTrue("The diagnostic left an unauthenticated socket open", peerClosed.await(1, TimeUnit.SECONDS))
            peer.join(500)
        }
    }

    private class Fake {
        var now = 0L
        var usb: Boolean? = true
        var alive = false
        var connections = 0
        var portChecks = 0
        var lastPortBudget = 0L
        var portState = OfflineAdbHealth.PortState.UNKNOWN
        var connect: (Long) -> Boolean = { false }

        fun operations() = OfflineAdbHealth.Operations(
            nowMs = { now }, pause = { now += it }, usbDebugging = { usb },
            probe = { alive },
            connect = { timeout -> connections++; connect(timeout) },
            inspectPort = { timeout ->
                portChecks++; lastPortBudget = timeout
                now += timeout
                portState
            },
        )
    }
}
