package com.example.carbridge.adb

import io.github.muntashirakon.adb.AdbConnection
import java.io.IOException

internal object AdbTransportPolicy {
    fun requireAuthenticatedMode(connection: AdbConnection, mode: AdbConnectionMode) {
        if (!connection.isConnectionEstablished) throw IOException("ADB не подтвердил подключение")
        when (mode) {
            AdbConnectionMode.TLS -> if (!connection.isTls) {
                throw IOException("Укажите TLS-порт подключения из «Беспроводная отладка»")
            }
            AdbConnectionMode.LOCAL_TCP -> {
                if (connection.isTls) throw IOException("Для офлайн режима нужен подготовленный локальный TCP-порт, не TLS")
                if (!connection.isRsaAuthenticated) {
                    throw IOException("Локальный ADB не подтвердил авторизацию сохранённым RSA-ключом")
                }
            }
        }
    }
}
