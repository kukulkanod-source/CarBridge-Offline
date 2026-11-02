package com.example.carbridge.adb

import android.content.Context
import android.util.AtomicFile
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Software RSA is required by ADB's raw RSA token signature and bundled Conscrypt. */
internal data class AdbIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    companion object {
        private const val MAGIC = 0x43424131 // CBA1

        fun loadOrCreate(context: Context): AdbIdentity {
            val directory = File(context.noBackupFilesDir, "adb")
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Не удалось создать приватное хранилище ключа ADB")
            }
            val file = AtomicFile(File(directory, "identity.bin"))
            // AtomicFile.openRead also restores a previous complete file after an interrupted write.
            val stored = try { file.openRead() } catch (_: java.io.FileNotFoundException) { null }
            if (stored != null) {
                return stored.use { stream ->
                    DataInputStream(stream).use { data ->
                        if (data.readInt() != MAGIC) throw IOException("Повреждён формат ключа ADB")
                        val key = KeyFactory.getInstance("RSA").generatePrivate(
                            PKCS8EncodedKeySpec(readBlob(data)))
                        val cert = CertificateFactory.getInstance("X.509")
                            .generateCertificate(readBlob(data).inputStream()) as X509Certificate
                        AdbIdentity(key, cert).also { it.verify() }
                    }
                }
            }
            val generated = generate()
            val stream = file.startWrite()
            try {
                val data = DataOutputStream(stream)
                data.writeInt(MAGIC)
                for (bytes in arrayOf(generated.privateKey.encoded, generated.certificate.encoded)) {
                    data.writeInt(bytes.size)
                    data.write(bytes)
                }
                data.flush()
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
            // Do not regenerate this identity on disconnect or a failed pairing attempt.
            return generated
        }

        internal fun generate(): AdbIdentity {
            val random = SecureRandom()
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
            val subject = X500Principal("CN=CarBridge ADB")
            val now = System.currentTimeMillis()
            val holder = JcaX509v3CertificateBuilder(subject, BigInteger(128, random).setBit(127),
                Date(now - 86_400_000L), Date(now + 20L * 365 * 86_400_000L), subject, pair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            val cert = JcaX509CertificateConverter().getCertificate(holder)
            return AdbIdentity(pair.private, cert).also { it.verify() }
        }

        private fun readBlob(data: DataInputStream): ByteArray {
            val length = data.readInt()
            if (length !in 1..32_768) throw IOException("Повреждены данные ключа ADB")
            return ByteArray(length).also { data.readFully(it) }
        }
    }

    private fun verify() {
        certificate.verify(certificate.publicKey)
        val probe = "CarBridge ADB identity check".toByteArray(Charsets.US_ASCII)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey); update(probe); sign()
        }
        if (!Signature.getInstance("SHA256withRSA").run {
                initVerify(certificate.publicKey); update(probe); verify(signature)
            }) throw IOException("Закрытый ключ ADB не соответствует сертификату")
    }
}
