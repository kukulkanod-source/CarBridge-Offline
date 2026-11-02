package com.example.carbridge.scrcpy

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Minimal ADB SYNC v1 upload. All SYNC lengths are little endian. */
object ScrcpySync {
    fun send(input: InputStream, output: OutputStream, path: String, data: ByteArray,
             modifiedSeconds: Int = (System.currentTimeMillis() / 1000).toInt()) {
        val target = "$path,33188".toByteArray(Charsets.UTF_8) // regular file, 0644
        require(target.size <= 1024 && '\u0000' !in path)
        header(output, "SEND", target.size)
        output.write(target)
        var offset = 0
        while (offset < data.size) {
            val length = minOf(64 * 1024, data.size - offset)
            header(output, "DATA", length)
            output.write(data, offset, length)
            offset += length
        }
        header(output, "DONE", modifiedSeconds)
        output.flush()
        val response = ByteArray(8)
        DataInputStream(input).readFully(response)
        val type = String(response, 0, 4, Charsets.US_ASCII)
        val length = (response[4].toInt() and 255) or ((response[5].toInt() and 255) shl 8) or
            ((response[6].toInt() and 255) shl 16) or ((response[7].toInt() and 255) shl 24)
        if (type == "FAIL") {
            if (length !in 0..65536) throw IOException("Invalid ADB SYNC failure length")
            val error = ByteArray(length)
            DataInputStream(input).readFully(error)
            throw IOException("ADB SYNC: ${String(error, Charsets.UTF_8)}")
        }
        if (type != "OKAY" || length != 0) throw IOException("Invalid ADB SYNC response: $type/$length")
        header(output, "QUIT", 0)
        output.flush()
    }

    private fun header(output: OutputStream, type: String, size: Int) {
        output.write(type.toByteArray(Charsets.US_ASCII))
        output.write(byteArrayOf(size.toByte(), (size ushr 8).toByte(), (size ushr 16).toByte(), (size ushr 24).toByte()))
    }
}
