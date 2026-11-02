package com.example.carbridge.adb

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec

class AdbIdentityTest {
    @Test fun generatedIdentitySurvivesThePersistedPkcs8AndX509RoundTrip() {
        val identity = AdbIdentity.generate()
        val restoredKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(identity.privateKey.encoded))
        val restoredCert = CertificateFactory.getInstance("X.509")
            .generateCertificate(identity.certificate.encoded.inputStream())
        restoredCert.verify(restoredCert.publicKey)
        assertEquals(2048, (restoredCert.publicKey as RSAPublicKey).modulus.bitLength())
        val token = ByteArray(20) { it.toByte() }
        val signed = Signature.getInstance("SHA256withRSA").run {
            initSign(restoredKey); update(token); sign()
        }
        assertTrue(Signature.getInstance("SHA256withRSA").run {
            initVerify(restoredCert.publicKey); update(token); verify(signed)
        })
        assertArrayEquals(identity.certificate.publicKey.encoded, restoredCert.publicKey.encoded)
    }
}
