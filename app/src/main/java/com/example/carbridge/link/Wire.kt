package com.example.carbridge.link

/**
 * Формат потока (big-endian):
 *   внешний кадр:  u32 канал | u32 длина | inner[длина]
 *   CMD/TOUCH inner:  u16 длина | u16 0 | u32 тип | тело
 *   прочие inner:     u32 длина | u32 метка времени | u32 тип | тело
 * Канал совпадает со старшими 16 битами типа сообщения.
 */
object Wire {
    const val CH_CMD = 1
    const val CH_VIDEO = 2
    const val CH_MEDIA = 3
    const val CH_TTS = 4
    const val CH_VR = 5
    const val CH_TOUCH = 6

    const val MAX_OUTER = 64 * 1024 * 1024
    const val MAX_CMD_DECLARED = 1024 * 1024
    const val WRITE_CHUNK = 16384

    fun isCmdStyle(ch: Int) = ch == CH_CMD || ch == CH_TOUCH
    fun hdrLen(ch: Int) = if (isCmdStyle(ch)) 8 else 12

    fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    fun u32(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or
            ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)

    fun putU16(b: ByteArray, i: Int, v: Int) { b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte() }
    fun putU32(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 24).toByte(); b[i + 1] = (v shr 16).toByte()
        b[i + 2] = (v shr 8).toByte(); b[i + 3] = v.toByte()
    }

    fun cmdInner(type: Int, body: ByteArray?): ByteArray {
        val n = body?.size ?: 0
        require(n <= 65535) { "CMD body too long: $n" }
        val r = ByteArray(8 + n)
        putU16(r, 0, n); putU32(r, 4, type)
        if (body != null) System.arraycopy(body, 0, r, 8, n)
        return r
    }

    fun dataInner(type: Int, timestamp: Int, body: ByteArray?): ByteArray {
        val n = body?.size ?: 0
        val r = ByteArray(12 + n)
        putU32(r, 0, n); putU32(r, 4, timestamp); putU32(r, 8, type)
        if (body != null) System.arraycopy(body, 0, r, 12, n)
        return r
    }

    fun outer(channel: Int, inner: ByteArray): ByteArray {
        val r = ByteArray(8 + inner.size)
        putU32(r, 0, channel); putU32(r, 4, inner.size)
        System.arraycopy(inner, 0, r, 8, inner.size)
        return r
    }

    class Cmd(val type: Int, val body: ByteArray)

    fun parseCmd(inner: ByteArray): Cmd? {
        if (inner.size < 8) return null
        val len = u16(inner, 0)
        val avail = inner.size - 8
        return Cmd(u32(inner, 4), inner.copyOfRange(8, 8 + minOf(len, avail)))
    }
}

/** Режет входящий байтовый поток на внешние кадры (канал, inner). */
class FrameReader {
    private var buf = ByteArray(65536)
    private var start = 0
    private var end = 0

    fun feed(d: ByteArray, off: Int, len: Int, sink: (Int, ByteArray) -> Unit) {
        ensure(len)
        System.arraycopy(d, off, buf, end, len)
        end += len
        while (end - start >= 8) {
            val ch = Wire.u32(buf, start)
            val l = Wire.u32(buf, start + 4)
            if (ch !in 0..255 || l < 0 || l > Wire.MAX_OUTER) {
                throw IllegalStateException("плохой внешний заголовок: ch=$ch len=$l")
            }
            if (end - start < 8 + l) break
            val payload = buf.copyOfRange(start + 8, start + 8 + l)
            start += 8 + l
            sink(ch, payload)
        }
        if (start == end) { start = 0; end = 0 }
    }

    private fun ensure(n: Int) {
        if (end + n <= buf.size) return
        val live = end - start
        if (live + n <= buf.size) {
            System.arraycopy(buf, start, buf, 0, live)
        } else {
            val nb = ByteArray(maxOf(buf.size * 2, live + n))
            System.arraycopy(buf, start, nb, 0, live)
            buf = nb
        }
        start = 0; end = live
    }
}

/** Внутри внешнего кадра сообщения могут быть склеены или разорваны: собираем по заголовку. */
class InnerAssembler {
    private val pending = HashMap<Int, ByteArray>()

    fun feed(ch: Int, data: ByteArray, sink: (Int, ByteArray) -> Unit) {
        val buf = pending.remove(ch)?.let { it + data } ?: data
        val hdr = Wire.hdrLen(ch)
        val cmdStyle = Wire.isCmdStyle(ch)
        var pos = 0
        while (buf.size - pos >= hdr) {
            val declared = if (cmdStyle) Wire.u16(buf, pos) else Wire.u32(buf, pos)
            val typeOff = pos + if (cmdStyle) 4 else 8
            val limit = if (cmdStyle) Wire.MAX_CMD_DECLARED else Wire.MAX_OUTER
            if (declared < 0 || declared > limit || (Wire.u32(buf, typeOff) ushr 16) != ch) {
                return // рассинхрон: выбрасываем хвост
            }
            val endPos = pos + hdr + declared
            if (endPos > buf.size) break
            sink(ch, buf.copyOfRange(pos, endPos))
            pos = endPos
        }
        if (pos < buf.size) pending[ch] = buf.copyOfRange(pos, buf.size)
    }
}
