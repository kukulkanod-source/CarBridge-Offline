package com.example.carbridge.link

import android.util.Log
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/** Ответ на HU_AUTHEN_REQUEST; null означает невозможность авторизации. */
interface HuAuthenticator {
    fun respond(random: String): String?
}

object HuAuthProvider {
    @Volatile
    var authenticator: HuAuthenticator = CarLifeAuthenticator()
}

/**
 * Совместимость с HuAuth из headswap 2.2.0 (Handshake2.java).
 * mdInfo должен совпадать с brand + model + sdk из отправленного MD_INFO.
 * RSA здесь — операция private-key ENCRYPT_MODE с PKCS1Padding, без SHA-подписи.
 */
class CarLifeAuthenticator(
    private val mdInfo: () -> String = { Msgs.deviceAuthKey() },
    private val mdVersion: String = Msgs.MD_VERSION
) : HuAuthenticator {
    override fun respond(random: String): String? = try {
        val parts = javaSplit(random, ";")
        if (parts.size >= 2 && parts[0] == "2.0" && parts[1].isNotEmpty()) {
            MessageDigest.getInstance("MD5")
                .digest(("CARLIFE" + mdInfo() + parts[1]).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        } else {
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, privateKey)
            Base64.getEncoder().encodeToString(cipher.doFinal(versionVerify(random).toByteArray(Charsets.UTF_8)))
        }
    } catch (e: Exception) {
        Log.e("CarBridge/Auth", "Не удалось сформировать ответ авторизации", e)
        null
    }

    internal fun versionVerify(value: String): String {
        val index = value.indexOf('_')
        if (index < 0) return value
        val suffix = value.substring(index)
        val prefix = value.replace(suffix, "")
        val requested = javaSplit(suffix.replace("_", ""), ".")
        val supported = javaSplit(mdVersion, ".")
        if (requested.size != supported.size) return prefix + "_00.00.00"
        for (i in supported.indices) {
            val hu = requested[i].toIntOrNull() ?: continue
            val md = supported[i].toIntOrNull() ?: continue
            if (hu > md) return prefix + "_00.00.00"
            if (hu < md) return value
        }
        return value
    }

    private fun javaSplit(value: String, separator: String): List<String> {
        val parts = value.split(separator)
        return if (parts.size == 1) parts else parts.dropLastWhile { it.isEmpty() }
    }

    private companion object {
        val privateKey by lazy {
            KeyFactory.getInstance("RSA").generatePrivate(
                PKCS8EncodedKeySpec(Base64.getDecoder().decode(PRIVATE_KEY)))
        }
        // Ключ совместимости из предоставленного APK, не ключ приложения CarBridge.
        const val PRIVATE_KEY = "MIICdwIBADANBgkqhkiG9w0BAQEFAASCAmEwggJdAgEAAoGBALzoBR3Io5wxQH+IMy0ekUE50s9ZOEPOI/y3bSwXSZEqU0EgDGVRnFBYhr8MSEkv2THTn5HKsUM3gHd3rpQhRG2JrcaFY+iIEJs2iueXvfKd8UqTUFQOZrc7xCz1npg4KResY4VAEN+W19ez/DsKiCS5LaxmwE844xWibTgjspY9AgMBAAECgYA7C/cVcTKaztZPRr5gWw6iKbXYNBYfM58SDi+kuX64TleJor/dJ55JivJLY0ZxAfDM304gXw/7Z6zTKui5ypA9duYu1Pohk/e5UUMFr0+cEyWzTjCJp/XaA/gEhH8g311qeWcALSo6q55vYtTJpc/OUn98nlsrOMv4XLgaBI2fwQJBAPBw7TkR4Xuvue2tfxh2Fn2x2ONJSRjckS7EipfdLSsgZCbwdQ2XL/k6VX0lMfQaLQQ6YCF/vrALlgkXFBuzFpECQQDJIWJGcoM95ms546hHBzu7DONmEpEh9b9nMEqRmOkzkitxvGApv1VjvZaX6gMrSANADmoz4lSTGXFea4nviJLtAkEA29v3H4NZMveJxWsrV5vLjx5MG/FMdP5jh2dS7/DgN5pD2lNwRYAk7vnHaErVtccluMMEWj1siZ/ejutaiMWm8QJBAIMM+4nHZ3hXwJoRmj3dmq/AMBL8GhC0nShRMOU5awmtPh13jnjlMHAywgLt+W6kF2oPemegG1dVhqbtDw1CQekCQH3xugE28LS6e29233PUBdipwoyRcbDeth70WDS3l4bl7XlWbTXOWF/D28ZZ49NSr1dRhAQwM8VVC/gHdvNpcqA="
    }
}

object UnconfiguredAuthenticator : HuAuthenticator {
    override fun respond(random: String): String? {
        Log.e("CarBridge/Auth", "HuAuthenticator отключён")
        return null
    }
}
