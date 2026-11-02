package com.example.carbridge.link.proto

import java.io.ByteArrayOutputStream

/** Минимальный protobuf-писатель: varint и length-delimited поля. */
class PbWriter {
    private val out = ByteArrayOutputStream()

    private fun varint(v: Long) {
        var x = v
        while ((x and 0x7FL.inv()) != 0L) {
            out.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
    }

    private fun tag(num: Int, wire: Int) = varint(((num shl 3) or wire).toLong())

    fun int32(num: Int, v: Int): PbWriter { tag(num, 0); varint(v.toLong()); return this }
    fun bool(num: Int, v: Boolean) = int32(num, if (v) 1 else 0)
    fun bytes(num: Int, b: ByteArray): PbWriter { tag(num, 2); varint(b.size.toLong()); out.write(b); return this }
    fun string(num: Int, s: String) = bytes(num, s.toByteArray(Charsets.UTF_8))
    fun toByteArray(): ByteArray = out.toByteArray()
}

class PbField(val num: Int, val wire: Int, val value: Long, val data: ByteArray?)

/** Минимальный protobuf-читатель. */
object PbReader {
    fun parse(b: ByteArray): List<PbField>? {
        val res = ArrayList<PbField>()
        val pos = intArrayOf(0)
        try {
            while (pos[0] < b.size) {
                val tag = varint(b, pos)
                val num = (tag ushr 3).toInt()
                when (val wire = (tag and 7).toInt()) {
                    0 -> res.add(PbField(num, wire, varint(b, pos), null))
                    1 -> { res.add(PbField(num, wire, 0, b.copyOfRange(pos[0], pos[0] + 8))); pos[0] += 8 }
                    2 -> {
                        val len = varint(b, pos).toInt()
                        if (len < 0 || pos[0] + len > b.size) return null
                        res.add(PbField(num, wire, 0, b.copyOfRange(pos[0], pos[0] + len)))
                        pos[0] += len
                    }
                    5 -> { res.add(PbField(num, wire, 0, b.copyOfRange(pos[0], pos[0] + 4))); pos[0] += 4 }
                    else -> return null
                }
            }
        } catch (e: IndexOutOfBoundsException) { return null }
        return res
    }

    private fun varint(b: ByteArray, pos: IntArray): Long {
        var shift = 0
        var r = 0L
        while (true) {
            val x = b[pos[0]++].toInt() and 0xFF
            r = r or ((x and 0x7F).toLong() shl shift)
            if ((x and 0x80) == 0) return r
            shift += 7
            if (shift > 63) throw IndexOutOfBoundsException("varint")
        }
    }

    fun int(f: List<PbField>, num: Int): Int? =
        f.firstOrNull { it.num == num && it.wire == 0 }?.value?.toInt()

    fun str(f: List<PbField>, num: Int): String? =
        f.firstOrNull { it.num == num && it.wire == 2 }?.data?.toString(Charsets.UTF_8)

    fun all(f: List<PbField>, num: Int): List<PbField> = f.filter { it.num == num }
}
