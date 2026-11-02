package com.example.carbridge.scrcpy

import com.example.carbridge.scrcpy.ScrcpyProtocol
import com.example.carbridge.scrcpy.ScrcpyProtocol.VideoEvent
import com.example.carbridge.scrcpy.ScrcpySync
import java.io.*

private fun bytes(write: DataOutputStream.() -> Unit): ByteArray {
    val out = ByteArrayOutputStream()
    DataOutputStream(out).apply(write).flush()
    return out.toByteArray()
}

private inline fun mustReject(block: () -> Unit) {
    try { block() } catch (_: IOException) { return }
    error("Malformed or truncated protocol input was accepted")
}

private fun fragmented(data: ByteArray): InputStream = object : ByteArrayInputStream(data) {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, minOf(1, length))
}

class ScrcpyProtocolTest {
@org.junit.Test
fun scrcpyFiveWireProtocolAndSyncUpload() {
    val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 31)
    val idr = byteArrayOf(0, 0, 0, 1, 0x65, 8)
    val pts = (1L shl 40) + 987654
    val fixture = bytes {
        writeInt(ScrcpyProtocol.H264)
        writeInt(0x80000001.toInt()); writeInt(1280); writeInt(720)
        writeLong(1L shl 62); writeInt(sps.size); write(sps)
        writeLong((1L shl 61) or pts); writeInt(idr.size); write(idr)
        writeLong(pts + 33333); writeInt(2); write(byteArrayOf(0x41, 9))
        writeInt(Int.MIN_VALUE); writeInt(640); writeInt(360)
    }
    val reader = ScrcpyProtocol.VideoReader(fragmented(fixture))
    reader.readCodec()
    check(reader.read() == VideoEvent.Session(1280, 720, true))
    (reader.read() as VideoEvent.Frame).also {
        check(it.config && !it.key && it.ptsUs == 0L && it.data.contentEquals(sps))
    }
    (reader.read() as VideoEvent.Frame).also {
        check(!it.config && it.key && it.ptsUs == pts && it.data.contentEquals(idr))
    }
    (reader.read() as VideoEvent.Frame).also { check(!it.config && !it.key && it.ptsUs == pts + 33333) }
    check(reader.read() == VideoEvent.Session(640, 360, false))
    mustReject { reader.read() }
    mustReject { ScrcpyProtocol.VideoReader(ByteArrayInputStream(bytes { writeInt(0x68323635) })).readCodec() }
    for (size in listOf(0, -1, ScrcpyProtocol.MAX_PACKET + 1)) {
        mustReject { ScrcpyProtocol.VideoReader(ByteArrayInputStream(bytes { writeLong(1); writeInt(size) })).read() }
    }
    mustReject { ScrcpyProtocol.VideoReader(ByteArrayInputStream(bytes { writeLong(4); writeInt(3); writeByte(1) })).read() }
    mustReject { ScrcpyProtocol.VideoReader(ByteArrayInputStream(bytes { writeInt(Int.MIN_VALUE); writeInt(0); writeInt(720) })).read() }

    val touch = ScrcpyProtocol.touch(0, 1279, 719, 1280, 720)
    check(touch.size == 32)
    DataInputStream(ByteArrayInputStream(touch)).also {
        check(it.readUnsignedByte() == 2 && it.readUnsignedByte() == 0)
        check(it.readLong() == -2L)
        check(it.readInt() == 1279 && it.readInt() == 719)
        check(it.readUnsignedShort() == 1280 && it.readUnsignedShort() == 720)
        check(it.readUnsignedShort() == 65535 && it.readInt() == 0 && it.readInt() == 0)
        check(it.available() == 0)
    }
    DataInputStream(ByteArrayInputStream(ScrcpyProtocol.touch(1, -1, 9999, 800, 480))).also {
        it.skipBytes(10)
        check(it.readInt() == 0 && it.readInt() == 479)
        it.skipBytes(4)
        check(it.readUnsignedShort() == 0)
    }
    val cancel = ScrcpyProtocol.touch(3, 12, 34, 800, 480)
    check(cancel[0] == 2.toByte() && cancel[1] == 3.toByte())
    check(cancel[22] == 0.toByte() && cancel[23] == 0.toByte())
    check(ScrcpyProtocol.key(0, 187).contentEquals(bytes { writeByte(0); writeByte(0); writeInt(187); writeInt(0); writeInt(0) }))
    check(ScrcpyProtocol.key(1, 3)[1] == 1.toByte())
    check(ScrcpyProtocol.displayPower(false).contentEquals(byteArrayOf(10, 0)))
    check(ScrcpyProtocol.displayPower(true).contentEquals(byteArrayOf(10, 1)))
    check(ScrcpyProtocol.resetVideo().contentEquals(byteArrayOf(17)))
    val app = "com.example.launcher"
    check(ScrcpyProtocol.startApp(app).contentEquals(byteArrayOf(16, app.length.toByte()) + app.toByteArray()))
    for (invalid in listOf("+com.app", "?app", "com.app; reboot", "", "a".repeat(256))) {
        check(runCatching { ScrcpyProtocol.startApp(invalid) }.isFailure)
    }
    val notifications = DataInputStream(ByteArrayInputStream(bytes {
        writeByte(0); writeInt(3); write(byteArrayOf(1, 2, 3))
        writeByte(1); writeLong(123)
        writeByte(2); writeShort(4); writeShort(2); writeShort(0x1234)
    }))
    repeat(3) { ScrcpyProtocol.readDeviceMessage(notifications) }
    check(notifications.available() == 0)
    mustReject { ScrcpyProtocol.readDeviceMessage(DataInputStream(ByteArrayInputStream(byteArrayOf(99)))) }

    val syncOut = ByteArrayOutputStream()
    val content = ByteArray(65536 + 17) { it.toByte() }
    ScrcpySync.send(fragmented("OKAY".toByteArray() + ByteArray(4)), syncOut,
        "/data/local/tmp/carbridge-scrcpy-test.jar", content, 1234)
    val sync = DataInputStream(ByteArrayInputStream(syncOut.toByteArray()))
    fun littleInt(): Int = Integer.reverseBytes(sync.readInt())
    fun id(): String = ByteArray(4).also { sync.readFully(it) }.toString(Charsets.US_ASCII)
    check(id() == "SEND")
    val path = ByteArray(littleInt()).also { sync.readFully(it) }.toString(Charsets.UTF_8)
    check(path == "/data/local/tmp/carbridge-scrcpy-test.jar,33188")
    check(id() == "DATA" && littleInt() == 65536)
    val first = ByteArray(65536).also { sync.readFully(it) }
    check(first.contentEquals(content.copyOfRange(0, 65536)))
    check(id() == "DATA" && littleInt() == 17)
    val second = ByteArray(17).also { sync.readFully(it) }
    check(second.contentEquals(content.copyOfRange(65536, content.size)))
    check(id() == "DONE" && littleInt() == 1234)
    check(id() == "QUIT" && littleInt() == 0 && sync.available() == 0)
    mustReject { ScrcpySync.send(ByteArrayInputStream("FAIL".toByteArray() + byteArrayOf(3, 0, 0, 0) + "bad".toByteArray()), ByteArrayOutputStream(), "/tmp/a", byteArrayOf(1)) }
    println("PASS scrcpy 5.0.1: fragmented codec/session/config/key/PTS parsing, resize metadata, malformed/truncated input; touch/key/start-app/display-power/reset control bytes; device notifications; ADB SYNC chunking/little-endian/error handling")
}


}

