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
