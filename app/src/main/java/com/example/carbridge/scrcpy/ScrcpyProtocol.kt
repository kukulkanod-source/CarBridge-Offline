package com.example.carbridge.scrcpy

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream

/** Wire format pinned to Genymobile scrcpy v5.0.1, not the pre-5.0 packet flags. */
object ScrcpyProtocol {
    const val H264 = 0x68323634
    const val MAX_PACKET = 32 * 1024 * 1024

    sealed class VideoEvent {
        data class Session(val width: Int, val height: Int, val clientResized: Boolean) : VideoEvent()
        data class Frame(val data: ByteArray, val config: Boolean, val key: Boolean, val ptsUs: Long) : VideoEvent()
    }

    class VideoReader(input: InputStream) {
        private val input = DataInputStream(input)

        fun readCodec() {
            val codec = input.readInt()
            if (codec != H264) throw IOException("scrcpy: expected H.264, received codec 0x${codec.toUInt().toString(16)}")
        }

        fun read(): VideoEvent {
            val a = input.readInt()
            val b = input.readInt()
            val c = input.readInt()
            if (a < 0) {
                if (b !in 1..65535 || c !in 1..65535) throw IOException("Invalid scrcpy session size: ${b}x$c")
                return VideoEvent.Session(b, c, a and 1 != 0)
            }
            val length = c.toLong() and 0xffffffffL
            if (length !in 1..MAX_PACKET.toLong()) throw IOException("Invalid scrcpy packet length: $length")
            val data = ByteArray(length.toInt())
            input.readFully(data)
            val flagsAndPts = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
            val config = a and 0x40000000 != 0
            return VideoEvent.Frame(data, config, a and 0x20000000 != 0,
                if (config) 0L else flagsAndPts and 0x1fffffffffffffffL)
        }
    }

    /** Single finger, position in the current encoded video's coordinate system. */
    fun touch(action: Int, x: Int, y: Int, width: Int, height: Int): ByteArray =
        touchPointer(action, -2L, x, y, width, height)

    /** scrcpy derives ACTION_POINTER_DOWN/UP from these plain DOWN/UP messages. */
    fun touchPointer(action: Int, pointerId: Long, x: Int, y: Int, width: Int, height: Int): ByteArray {
        require(action in 0..3)
        require(pointerId != -1L) { "Mouse pointer id is not a finger" }
        require(width in 1..65535 && height in 1..65535)
        return packet {
            writeByte(2)
            writeByte(action)
            writeLong(pointerId)
            writeInt(x.coerceIn(0, width - 1))
            writeInt(y.coerceIn(0, height - 1))
            writeShort(width)
            writeShort(height)
            writeShort(if (action == 1 || action == 3) 0 else 0xffff)
            writeInt(0) // actionButton
            writeInt(0) // buttons: finger events must not carry mouse buttons
        }
    }

    fun key(action: Int, keyCode: Int): ByteArray = packet {
        require(action == 0 || action == 1)
        writeByte(0)
        writeByte(action)
        writeInt(keyCode)
        writeInt(0) // repeat
        writeInt(0) // metaState
    }

    /** Clipboard + paste supports UTF-8, including Cyrillic, unlike key-character injection. */
    fun pasteText(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..16384)
        return packet {
            writeByte(9); writeLong(0L); writeByte(1); writeInt(bytes.size); write(bytes)
        }
    }

    fun displayPower(on: Boolean) = byteArrayOf(10, if (on) 1 else 0)
    fun resetVideo() = byteArrayOf(17)

    fun startApp(packageName: String): ByteArray {
        // Do not expose scrcpy's '?' name-search and '+' force-stop modifiers.
        require(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*").matches(packageName)) {
            "Invalid package name"
        }
        val bytes = packageName.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..255) { "Package name is too long" }
        return byteArrayOf(16, bytes.size.toByte()) + bytes
    }

    /** Drain optional server notifications without copying clipboard contents into logs. */
    fun readDeviceMessage(input: DataInputStream) {
        when (val type = input.readUnsignedByte()) {
            0 -> {
                val length = input.readInt()
                if (length !in 0..(256 * 1024 - 5)) throw IOException("Invalid clipboard notification length")
                skipFully(input, length)
            }
            1 -> input.readLong()
            2 -> {
                input.readUnsignedShort() // UHID id
                skipFully(input, input.readUnsignedShort())
            }
            else -> throw IOException("Unknown scrcpy device message: $type")
        }
    }

    private fun skipFully(input: DataInputStream, count: Int) {
        var remaining = count
        val buffer = ByteArray(minOf(8192, count))
        while (remaining > 0) {
            val size = minOf(buffer.size, remaining)
            input.readFully(buffer, 0, size)
            remaining -= size
        }
    }

    private inline fun packet(write: DataOutputStream.() -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).apply(write).flush()
        return buffer.toByteArray()
    }
}
