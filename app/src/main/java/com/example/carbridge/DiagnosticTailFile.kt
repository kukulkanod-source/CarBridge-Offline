package com.example.carbridge

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Appends to an existing session only. In particular, never recreates a purged log. */
internal object DiagnosticTailFile {
    fun append(file: File?, line: ByteArray, limit: Long): Boolean {
        if (file == null || !file.isFile) return false
        return try {
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                if (channel.size() + line.size > limit) return false
                val buffer = ByteBuffer.wrap(line)
                while (buffer.hasRemaining()) channel.write(buffer)
                true
            }
        } catch (_: Exception) { false }
    }
}
