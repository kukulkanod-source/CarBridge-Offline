package com.example.carbridge.adb

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** One multiplexed ADB service. Closing either stream closes this channel, not the session. */
interface AdbChannel : Closeable {
    val input: InputStream
    val output: OutputStream
}
