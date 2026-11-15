package com.example.carbridge.link

/** Приведение Annex-B потока MediaCodec к виду, который принимают аппаратные декодеры ГУ. */
object H264 {
    private val SC4 = byteArrayOf(0, 0, 0, 1)

    /** Возвращает список [позиция, длина стартового кода]. */
    fun startCodes(d: ByteArray): List<IntArray> {
        val r = ArrayList<IntArray>()
        var i = 0
        while (i + 2 < d.size) {
            if (d[i].toInt() == 0 && d[i + 1].toInt() == 0) {
                if (d[i + 2].toInt() == 1) { r.add(intArrayOf(i, 3)); i += 3; continue }
                if (i + 3 < d.size && d[i + 2].toInt() == 0 && d[i + 3].toInt() == 1) {
                    r.add(intArrayOf(i, 4)); i += 4; continue
                }
            }
            i++
        }
        return r
    }

    /** Все стартовые коды -> 4-байтные. */
    fun normalize(d: ByteArray): ByteArray {
        val sc = startCodes(d)
        if (sc.isEmpty() || sc.all { it[1] == 4 }) return d
        val out = java.io.ByteArrayOutputStream(d.size + sc.size)
        if (sc[0][0] > 0) out.write(d, 0, sc[0][0])
        for ((idx, c) in sc.withIndex()) {
            out.write(SC4)
            val from = c[0] + c[1]
            val to = if (idx + 1 < sc.size) sc[idx + 1][0] else d.size
            out.write(d, from, to - from)
        }
        return out.toByteArray()
    }

    fun nalTypes(d: ByteArray): List<Int> =
        startCodes(d).mapNotNull { c -> (c[0] + c[1]).takeIf { it < d.size }?.let { d[it].toInt() and 31 } }

    fun startsWithSps(d: ByteArray) = nalTypes(d).firstOrNull() == 7

    /** Repeat only missing parameter sets before the first video slice. Preserve AUD and in-band
     * SPS/PPS. Inspired by HSD v0.8.1 withAvcParameterSets (GPL-3.0); no changes to the encoder.
     */
    fun withParameterSets(data: ByteArray, config: ByteArray?): ByteArray {
        if (config == null) return data
        data class Nal(val start: Int, val end: Int, val type: Int)
        fun units(bytes: ByteArray): List<Nal> {
            val codes = startCodes(bytes)
            return codes.mapIndexedNotNull { index, code ->
                val header = code[0] + code[1]
                val end = codes.getOrNull(index + 1)?.get(0) ?: bytes.size
                if (header >= end) null else Nal(code[0], end, bytes[header].toInt() and 31)
            }
        }
        val nals = units(data)
        val slice = nals.indexOfFirst { it.type in 1..5 }
        if (slice < 0) return data
        val prefix = nals.take(slice)
        val hasSps = prefix.any { it.type == 7 }
        val hasPps = prefix.any { it.type == 8 }
        if (hasSps && hasPps) return data
        val parameters = units(config)
        fun parameter(type: Int) = parameters.lastOrNull { it.type == type }
            ?.let { config.copyOfRange(it.start, it.end) }
        val sps = if (hasSps) ByteArray(0) else parameter(7) ?: return data
        val pps = if (hasPps) ByteArray(0) else parameter(8) ?: return data
        val offset = when {
            !hasSps && hasPps -> prefix.first { it.type == 8 }.start
            hasSps -> prefix.last { it.type == 7 }.end
            else -> prefix.takeWhile { it.type == 9 }.lastOrNull()?.end ?: nals.first().start
        }
        return data.copyOfRange(0, offset) + sps + pps + data.copyOfRange(offset, data.size)
    }

    fun levelFor(w: Int, h: Int): Int {
        val mbs = ((w + 15) / 16) * ((h + 15) / 16)
        return when {
            mbs <= 3600 -> 31
            mbs <= 8192 -> 40
            mbs <= 8704 -> 42
            mbs <= 22080 -> 50
            else -> 51
        }
    }

    /**
     * Некоторые энкодеры выдают завышенный level_idc, который декодер ГУ отвергает.
     * Если level > 4.1, подменяем на подходящий размеру кадра; для Baseline ставим constraint_set0.
     */
    fun fixSps(d: ByteArray, w: Int, h: Int): ByteArray {
        var out = d
        for (c in startCodes(d)) {
            val p = c[0] + c[1]
            if (p + 3 < d.size && (d[p].toInt() and 31) == 7 && (d[p + 3].toInt() and 0xFF) > 41) {
                if (out === d) out = d.copyOf()
                if ((d[p + 1].toInt() and 0xFF) == 66) out[p + 2] = (out[p + 2].toInt() or 0x80).toByte()
                out[p + 3] = levelFor(w, h).toByte()
            }
        }
        return out
    }
}
