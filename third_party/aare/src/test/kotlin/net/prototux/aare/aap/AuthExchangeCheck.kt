package net.prototux.aare.aap

import aap_protobuf.service.control.message.AuthResponseOuterClass.AuthResponse
import aap_protobuf.service.control.message.ServiceDiscoveryResponseOuterClass.ServiceDiscoveryResponse
import net.prototux.aare.aap.cert.SelfSignedCertificate
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Exercises the real session reader/writer, TLS and framing without a device or network. */
object AuthExchangeCheck {
    @JvmStatic fun main(args: Array<String>) {
        runCase(-3)
        runCase(0)
        runCase(null) // Damaged protobuf must never be treated as status zero.
        println("AA AUTH exchange checks passed: rejection, success, malformed response")
    }

    private fun runCase(status: Int?) {
        val phoneId = SelfSignedCertificate.generate("test phone")
        val receiverId = SelfSignedCertificate.generate("test receiver")
        val phoneIn = PipedInputStream(65536)
        val receiverOut = PipedOutputStream(phoneIn)
        val receiverIn = PipedInputStream(65536)
        val phoneOut = PipedOutputStream(receiverIn)
        val closed = CountDownLatch(1)
        var closeReason = ""
        val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
        val transport = object : Transport {
            override val name = "test pipes"
            override val input = phoneIn
            override val output = phoneOut
            override fun close() { runCatching { phoneIn.close() }; runCatching { phoneOut.close() } }
        }
        val phone = AapSession(transport, PhoneIdentity(phoneId.certificate, phoneId.keyPair.private),
            PhoneDescription("test", "test"), object : AapSession.Callbacks {
                override fun onServiceDiscovery(session: AapSession, response: ServiceDiscoveryResponse): List<ServiceChannel> =
                    error("No discovery response was supplied")
                override fun onClosed(session: AapSession, reason: String, error: Throwable?) {
                    closeReason = reason
                    closed.countDown()
                }
            }, object : AapLog {
                override fun i(msg: String) { logs.add(msg) }
                override fun w(msg: String, t: Throwable?) { logs.add(msg) }
                override fun e(msg: String, t: Throwable?) { logs.add(msg) }
            }, SessionOptions(sendPings = false))
        val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "test-aa-peer").apply { isDaemon = true } }
        try {
            phone.start()
            worker.submit {
                val tls = Cryptor(receiverId.certificate, receiverId.keyPair.private, client = true)
                val reader = FrameReader(receiverIn) { if (tls.handshakeDone) tls else null }
                val writer = FrameWriter(receiverOut) { if (tls.handshakeDone) tls else null }
                fun plain(id: Int, body: ByteArray) = writer.write(0, false, false, AapMessage.plaintext(id, body))
                plain(ControlMsg.VERSION_REQUEST, byteArrayOf(0, 1, 0, 5))
                check(reader.read().id == ControlMsg.VERSION_RESPONSE)
                // Reproduce the adapter's plaintext ping with the encrypted flag before TLS.
                val ping = byteArrayOf(0, 11, 8, 1, 16, 0)
                receiverOut.write(byteArrayOf(0, 11, 0, ping.size.toByte()) + ping)
                receiverOut.flush()
                check(reader.read().id == ControlMsg.PING_RESPONSE)
                tls.beginHandshake()
                plain(ControlMsg.ENCAPSULATED_SSL, tls.handshake(byteArrayOf()))
                while (!tls.handshakeDone) {
                    val msg = reader.read()
                    check(msg.id == ControlMsg.ENCAPSULATED_SSL && !msg.encrypted)
                    val out = tls.handshake(msg.bodyBytes())
                    if (out.isNotEmpty()) plain(ControlMsg.ENCAPSULATED_SSL, out)
                }
                check(tls.peerCertificates.first().encoded.contentEquals(phoneId.certificate.encoded))
                val auth = if (status == null) byteArrayOf(8, 0x80.toByte())
                    else AuthResponse.newBuilder().setStatus(status).build().toByteArray()
                plain(ControlMsg.AUTH_COMPLETE, auth)
                if (status == 0) {
                    val discovery = reader.read()
                    check(discovery.id == ControlMsg.SERVICE_DISCOVERY_REQUEST && discovery.encrypted)
                }
            }.get(15, TimeUnit.SECONDS)
            if (status == 0) {
                check(phone.state == AapSession.State.DISCOVERY)
                check(!phone.certificateRejected)
            } else {
                check(closed.await(3, TimeUnit.SECONDS))
                check(phone.closedDuring == AapSession.State.AUTH)
                check(closeReason.contains(if (status == null) "malformed authentication response" else "status -3"))
                check(logs.none { it.contains("type=SERVICE_DISCOVERY_REQUEST") })
            }
        } finally {
            phone.stop()
            transport.close()
            runCatching { receiverOut.close() }; runCatching { receiverIn.close() }
            worker.shutdownNow()
        }
    }
}
