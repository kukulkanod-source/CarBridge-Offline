package com.example.carbridge.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch

class OfflineAdbActivationTest {
    @Test fun missingOrWrongRestartAcknowledgementDoesNotReachReconnect() {
        for (reply in listOf("", "restarting in TCP mode port: 1235\n", "restarting in TCP mode")) {
            var reconnected = false
            try {
                OfflineAdbActivation.activate(1234,
                    openService = { Reply(reply) }, disconnectTransport = { },
                    reconnectLocal = { _, _ -> reconnected = true; true }, checkCancelled = { })
                fail("Invalid ACK was accepted: $reply")
            } catch (_: IOException) { }
            assertFalse(reconnected)
        }
    }

    @Test fun acknowledgedRestartSurvivesCloseFailureAndReturnsOnlyAfterReconnect() {
        var attempts = 0
        var disconnected = false
        val port = OfflineAdbActivation.activate(1234,
            openService = { destination ->
                assertEquals("tcpip:1234", destination)
                Reply("restarting in TCP mode port: 1234\n", throwOnClose = true)
            },
            disconnectTransport = { disconnected = true },
            reconnectLocal = { selected, timeout ->
                assertTrue(disconnected)
                assertEquals(1234, selected)
                assertTrue(timeout in 1..2000)
                ++attempts == 2
            }, checkCancelled = { })
        assertEquals(1234, port)
        assertEquals(2, attempts)
    }

    @Test fun individualSocketTimeoutDoesNotAbortTheRestartReconnectBudget() {
        var attempts = 0
        val port = OfflineAdbActivation.activate(1234,
            openService = { Reply("restarting in TCP mode port: 1234\n") },
            disconnectTransport = { },
            reconnectLocal = { _, _ ->
                if (++attempts == 1) throw SocketTimeoutException("restart still in progress")
                true
            }, checkCancelled = { })
        assertEquals(1234, port)
        assertEquals(2, attempts)
    }

    @Test(timeout = 5000) fun acceptedUsbAckAndClosedPortConfirmDeactivationDespiteCloseFailure() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val port = server.localPort
            var disconnected = false
            OfflineAdbActivation.deactivate(port,
                openService = { destination ->
                    assertEquals("usb:", destination)
                    Reply("restarting in USB mode\n", throwOnClose = true)
                },
                disconnectTransport = { disconnected = true; server.close() },
                checkCancelled = { })
            assertTrue(disconnected)
        }
    }

    @Test(timeout = 11000) fun acknowledgedUsbCommandDoesNotClaimAnOpenPortWasDisabled() {
        ServerSocket(0, 100, InetAddress.getByName("127.0.0.1")).use { server ->
            try {
                OfflineAdbActivation.deactivate(server.localPort,
                    openService = { Reply("restarting in USB mode\n") },
                    disconnectTransport = { }, checkCancelled = { })
                fail("An open TCP listener must not be reported disabled")
            } catch (_: IOException) { }
            assertFalse(server.isClosed)
        }
    }

    @Test(timeout = 2000) fun stalledAcknowledgementIsUnblockedByTheWatchdog() {
        val closed = AtomicBoolean(false)
        val monitor = Object()
        val channel = object : AdbChannel {
            override val input = object : InputStream() {
                override fun read(): Int = synchronized(monitor) {
                    while (!closed.get()) monitor.wait()
                    -1
                }
            }
            override val output: OutputStream = ByteArrayOutputStream()
            override fun close() = synchronized(monitor) { closed.set(true); monitor.notifyAll() }
        }
        try {
            OfflineAdbActivation.requireAcknowledgement(channel, "restarting in USB mode", 50)
            fail("Missing ACK must time out")
        } catch (_: IOException) { }
        assertTrue(closed.get())
    }

    @Test(timeout = 4000) fun stuckChannelCloseCannotBlockTheNextAcknowledgementTimeout() {
        val release = CountDownLatch(1)
        fun stuckChannel() = object : AdbChannel {
            override val input = object : InputStream() {
                override fun read(): Int {
                    // Model a native stream that ignores thread interruption until its owner closes.
                    while (release.count != 0L) {
                        try { release.await() } catch (_: InterruptedException) { }
                    }
                    return -1
                }
            }
            override val output: OutputStream = ByteArrayOutputStream()
            override fun close() {
                while (release.count != 0L) {
                    try { release.await() } catch (_: InterruptedException) { }
                }
            }
        }
        try {
            repeat(2) {
                val started = System.nanoTime()
                try {
                    OfflineAdbActivation.requireAcknowledgement(stuckChannel(), "restarting in USB mode", 50)
                    fail("A stuck reader must time out")
                } catch (_: SocketTimeoutException) { }
                assertTrue("One stuck close disabled later timeouts",
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
            }
        } finally { release.countDown() }
    }

    private class Reply(text: String, private val throwOnClose: Boolean = false) : AdbChannel {
        override val input: InputStream = ByteArrayInputStream(text.toByteArray(Charsets.US_ASCII))
        override val output: OutputStream = ByteArrayOutputStream()
        override fun close() { if (throwOnClose) throw IOException("adbd restarted before CLSE") }
    }
}
