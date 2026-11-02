package com.example.carbridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps the car Wi-Fi passphrase encrypted with an app-local Android Keystore key. */
object CarWifiCredentials {
    enum class Security { OPEN, WPA2, WPA3 }

    data class Stored(val ssid: String, val passphrase: String, val security: Security)

    private const val PREFS = "carbridge_car_wifi"
    private const val KEY_ALIAS = "carbridge-car-wifi-aes"
    private const val IV_BYTES = 12

    fun read(context: Context): Stored? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ssid = prefs.getString("ssid", "")?.trim().orEmpty()
        if (ssid.isBlank()) return null
        val encoded = prefs.getString("passphrase", "").orEmpty()
        val passphrase = if (encoded.isBlank()) "" else decrypt(encoded)
        val security = runCatching { Security.valueOf(prefs.getString("security", Security.WPA2.name)!!) }
            .getOrDefault(Security.WPA2)
        return Stored(ssid, passphrase, security)
    }

    fun save(context: Context, ssid: String, passphrase: String, security: Security) {
        val normalizedSsid = ssid.trim()
        require(normalizedSsid.isNotEmpty()) { "Укажите SSID сети ГУ" }
        require(normalizedSsid.length <= 32) { "SSID не должен быть длиннее 32 символов" }
        when (security) {
            Security.OPEN -> require(passphrase.isEmpty()) { "Для открытой сети пароль не нужен" }
            Security.WPA2, Security.WPA3 -> require(passphrase.length in 8..63) {
                "Пароль WPA должен содержать 8–63 символа"
            }
        }
        val encrypted = if (passphrase.isEmpty()) "" else encrypt(passphrase)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ssid", normalizedSsid)
            .putString("passphrase", encrypted)
            .putString("security", security.name)
            .apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        try {
            val packed = Base64.decode(value, Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) throw IOException("Сохранённый пароль Wi-Fi повреждён")
            val iv = packed.copyOfRange(0, IV_BYTES)
            val encrypted = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (error: Exception) {
            throw IOException("Не удалось расшифровать пароль Wi-Fi. Сохраните его повторно.", error)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return generator.generateKey()
    }
}
