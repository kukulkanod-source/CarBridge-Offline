package com.example.carbridge.adb

import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import io.github.muntashirakon.adb.AdbConnection
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.Signature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The peer uses independently encoded ADB packets and verifies the real RSA AUTH response. */
class LocalAdbAuthenticationTest {
    @Test(timeout = 15000) fun legacyChallengeUsesPersistedIdentityAndSignsTheRawSha1Token() {
        val identity = AdbIdentity.generate()
        val persisted = identity.privateKey.encoded.copyOf()
        repeat(2) { attempt ->
            val original = "independent adb authentication challenge $attempt".toByteArray()
            val token = MessageDigest.getInstance("SHA-1").digest(original)
            Peer { socket ->
                assertEquals(CNXN, read(socket).command)
                send(socket, AUTH, 1, 0, token)
                val reply = read(socket)
                assertEquals(AUTH, reply.command)
                assertEquals(2, reply.arg0)
                assertEquals(256, reply.payload.size)
                // SHA1withRSA hashes original once. Rehashing the incoming token would fail this check.
                assertTrue(Signature.getInstance("SHA1withRSA").run {
                    initVerify(identity.certificate.publicKey)
                    update(original)
                    verify(reply.payload)
                })
                send(socket, CNXN, 0x01000001, 1 shl 20)
            }.use { peer ->
                peer.connection(identity).use { connection ->
                    assertTrue(connection.connect(2000, TimeUnit.MILLISECONDS, true))
                    AdbTransportPolicy.requireAuthenticatedMode(connection, AdbConnectionMode.LOCAL_TCP)
                    assertTrue(connection.isRsaAuthenticated)
                    assertFalse(connection.isTls)
                    try {
                        AdbTransportPolicy.requireAuthenticatedMode(connection, AdbConnectionMode.TLS)
                        fail("TLS mode accepted a legacy AUTH connection")
                    } catch (_: IOException) { }
                }
            }
            assertArrayEquals(persisted, identity.privateKey.encoded)
        }
    }

    @Test(timeout = 8000) fun plainCnxnWithoutAnRsaChallengeIsRejectedByLocalMode() {
        val identity = AdbIdentity.generate()
        Peer { socket ->
            assertEquals(CNXN, read(socket).command)
            send(socket, CNXN, 0x01000001, 1 shl 20)
        }.use { peer ->
            peer.connection(identity).use { connection ->
                assertTrue(connection.connect(2000, TimeUnit.MILLISECONDS, true))
                assertFalse(connection.isRsaAuthenticated)
                try {
                    AdbTransportPolicy.requireAuthenticatedMode(connection, AdbConnectionMode.LOCAL_TCP)
                    fail("Unauthenticated CNXN was accepted")
                } catch (_: IOException) { }
            }
        }
    }

    @Test(timeout = 8000) fun rejectedSignatureDoesNotFallBackToUploadingAnotherPublicKey() {
        val identity = AdbIdentity.generate()
        val token = ByteArray(20) { (it * 7).toByte() }
        Peer { socket ->
            assertEquals(CNXN, read(socket).command)
            send(socket, AUTH, 1, 0, token)
            assertEquals(2, read(socket).arg0)
            send(socket, AUTH, 1, 0, token) // The daemon does not recognize this identity.
        }.use { peer ->
            peer.connection(identity).use { connection ->
                try {
                    connection.connect(2000, TimeUnit.MILLISECONDS, true)
                    fail("Rejected identity must fail authorization")
                } catch (_: AdbAuthenticationFailedException) { }
                assertFalse(connection.isRsaAuthenticated)
            }
        }
    }

    private class Peer(handler: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val release = CountDownLatch(1)
        private val worker = Executors.newSingleThreadExecutor()
        private var accepted: Socket? = null
        private val result = worker.submit {
            server.accept().use { socket ->
                accepted = socket
                socket.soTimeout = 3000
                handler(socket)
                release.await(4, TimeUnit.SECONDS)
            }
        }

        fun connection(identity: AdbIdentity): AdbConnection =
            AdbConnection.Builder("127.0.0.1", server.localPort).setApi(34)
                .setConnectTimeoutMillis(2000)
                .setPrivateKey(identity.privateKey).setCertificate(identity.certificate).build()

        override fun close() {
            release.countDown()
            try { result.get(5, TimeUnit.SECONDS) }
            finally {
                accepted?.close()
                server.close()
                worker.shutdownNow()
            }
        }
    }

    private data class Packet(val command: Int, val arg0: Int, val payload: ByteArray)

    private companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541

        fun read(socket: Socket): Packet {
            val input = DataInputStream(socket.getInputStream())
            val header = ByteArray(24).also { input.readFully(it) }
            val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = fields.int
            val arg0 = fields.int
            fields.int
            val length = fields.int
            fields.int
            assertEquals(command.inv(), fields.int)
            assertTrue(length in 0..(1 shl 20))
            return Packet(command, arg0, ByteArray(length).also { input.readFully(it) })
        }

        fun send(socket: Socket, command: Int, arg0: Int, arg1: Int, payload: ByteArray = byteArrayOf()) {
            val checksum = payload.sumOf { it.toInt() and 255 }
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(payload.size)
                .putInt(checksum).putInt(command.inv()).array()
            socket.getOutputStream().run { write(header); write(payload); flush() }
        }
    }
}
