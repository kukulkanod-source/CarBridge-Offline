package net.prototux.aare.aap

import java.io.ByteArrayInputStream
import aap_protobuf.service.control.message.PingRequestOuterClass.PingRequest

/** Standalone regression checks; no external test framework or network required. */
object EarlyHandshakePingCheck {
    @JvmStatic fun main(args:Array<String>) {
        val observed=byteArrayOf(0,11,8,0xbb.toByte(),0xa6.toByte(),0x9c.toByte(),0x16,16,0)
        val expected=PingRequest.parseFrom(observed.copyOfRange(2,observed.size)).timestamp
        check(EarlyHandshakePing.timestamp(observed)==expected)
        check(EarlyHandshakePing.timestamp(byteArrayOf(0,11,8,0))==0L)
        check(EarlyHandshakePing.timestamp(byteArrayOf(0,11,8)+ByteArray(8){0xff.toByte()}+byteArrayOf(0x7f))==Long.MAX_VALUE)
        val rejected=listOf(
            observed.copyOf(observed.size-1), // Truncated bool.
            observed.copyOf().also { it[8]=1 }, // Requests a bug report.
            observed.copyOf().also { it[1]=4 }, // AUTH is never permitted.
            observed+byteArrayOf(24,0), // Unknown/extra field.
            byteArrayOf(0,11,8,0x80.toByte(),0), // Non-canonical varint.
            byteArrayOf(0,11,8)+ByteArray(9){0xff.toByte()}+byteArrayOf(1), // Overflow.
            byteArrayOf(0,11,8,0x80.toByte()), // Truncated timestamp.
            byteArrayOf(0,11,8,1,26,0) // Data field is forbidden.
        )
        rejected.forEach { check(EarlyHandshakePing.timestamp(it)==null) }
        fun frame(flags:Int,body:ByteArray)=byteArrayOf(0,flags.toByte(),0,body.size.toByte())+body
        val next=frame(3,byteArrayOf(0,1,0,1,0,5))
        var consumed=0
        val reader=FrameReader(ByteArrayInputStream(frame(11,observed)+next)){null}
        reader.onEarlyPlainPing={ consumed++;EarlyHandshakePing.timestamp(it)==expected }
        val msg=reader.read()
        check(consumed==1 && msg.id==ControlMsg.VERSION_REQUEST && !msg.encrypted)
        fun mustReject(bytes:ByteArray, enable:Boolean) {
            val r=FrameReader(ByteArrayInputStream(bytes)){null}
            if(enable)r.onEarlyPlainPing={ error("Forbidden frame reached compatibility hook") }
            try { r.read();error("Expected rejection") } catch(expected:AapException) { }
        }
        mustReject(frame(11,observed),false)
        mustReject(frame(11,rejected[1]),true)
        mustReject(frame(15,observed),true) // Control flag variant not allowlisted.
        mustReject(byteArrayOf(1,11,0,9)+observed,true) // Wrong channel.
        // A ping must not be consumed in the middle of another fragmented message.
        mustReject(byteArrayOf(0,1,0,2,0,0,0,4,0,1)+frame(11,observed),true)
        println("EarlyHandshakePing regression checks passed")
        val serverId=net.prototux.aare.aap.cert.SelfSignedCertificate.generate("test phone")
        val clientId=net.prototux.aare.aap.cert.SelfSignedCertificate.generate("test receiver")
        val server=Cryptor(serverId.certificate,serverId.keyPair.private)
        val client=Cryptor(clientId.certificate,clientId.keyPair.private,client=true)
        server.beginHandshake();client.beginHandshake()
        var toServer=client.handshake(byteArrayOf())
        for(i in 0 until 16) {
            val toClient=server.handshake(toServer)
            toServer=client.handshake(toClient)
            if(server.handshakeDone && client.handshakeDone)break
        }
        check(server.handshakeDone && client.handshakeDone)
        check(client.peerCertificates.first().encoded.contentEquals(serverId.certificate.encoded))
        check(server.peerCertificates.first().encoded.contentEquals(clientId.certificate.encoded))
        val plain="identity roundtrip".toByteArray()
        val record=server.encrypt(plain,0,plain.size)
        check(client.decrypt(record,0,record.size).contentEquals(plain))
        val reply=client.encrypt(plain,0,plain.size)
        check(server.decrypt(reply,0,reply.size).contentEquals(plain))
        println("TLS key-manager handshake and encrypted roundtrip checks passed")
    }
}
