package com.example.carbridge.adb

/** LOCAL_TCP is an explicit, authenticated offline mode; it is never a fallback from TLS. */
enum class AdbConnectionMode { TLS, LOCAL_TCP }

internal data class AdbEndpoint(val host: String, val port: Int, val mode: AdbConnectionMode)
