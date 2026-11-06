package com.example.carbridge.adb

import android.content.Context
import java.io.IOException

/** Disable only TLS wireless debugging, after the separate RSA local channel is verified. */
internal object OfflineWirelessCleanup {
    fun finish(context: Context, port: Int, checkCancelled: () -> Unit): String {
        require(port in 1024..65535) { "Не сохранён проверенный локальный порт ADB" }
        checkCancelled()
        if (!AdbSession.probeConnected("127.0.0.1", port, AdbConnectionMode.LOCAL_TCP, 2500))
            throw IOException("Локальный ADB не подтвердил доступ. Беспроводная отладка не выключалась.")
        checkCancelled()
        runCatching {
            AdbSession.open("shell:settings put global adb_wifi_enabled 0; settings get global adb_wifi_enabled", 5000).use {
                it.input.readBytes().toString(Charsets.UTF_8).trim()
            }
        }
        checkCancelled()
        // The shell can disappear during an OEM adbd transition. Success always requires a new probe.
        Thread.sleep(800)
        checkCancelled()
        val health = OfflineAdbHealth.checkLocal(context, port, budgetMs = 10000,
            cancelled = { runCatching(checkCancelled).isFailure })
        checkCancelled()
        if (!health.connected) throw IOException(
            "После отключения беспроводной отладки локальный доступ не подтвердился. Включите беспроводную отладку и повторите подготовку; сохранённый порт оставлен.")
        val state = runCatching {
            AdbSession.open("shell:settings get global adb_wifi_enabled", 3000).use {
                it.input.readBytes().toString(Charsets.UTF_8).trim()
            }
        }.getOrNull()
        return if (state == "0") "Офлайн-доступ проверен, беспроводная отладка выключена. Отладку по USB оставьте включённой."
        else "Офлайн-доступ проверен. Android не подтвердил выключение беспроводной отладки; её переключатель проверьте в настройках разработчика."
    }
}
