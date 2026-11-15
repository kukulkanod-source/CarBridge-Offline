package com.example.carbridge.link

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant

/** Public metadata only: no alias, distinguished names, raw certificates or private keys. */
internal object AaPhoneCredentialSummary {
    enum class Source(val label: String) { OWN("self-signed"), ANDROID("Android KeyChain") }

    fun format(cert: X509Certificate, chainLength: Int, source: Source, now: Instant = Instant.now()): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val from = cert.notBefore.toInstant()
        val until = cert.notAfter.toInstant()
        val timeValid = !now.isBefore(from) && !now.isAfter(until)
        // Keep this label free of secret markers recognised by DiagnosticMessageFilter.
        return "AA phone TLS credential; source=${source.label}; sha256=$hash; chainLength=$chainLength; " +
            "algorithm=${cert.publicKey.algorithm}; selfIssued=${cert.subjectX500Principal == cert.issuerX500Principal}; " +
            "timeValid=$timeValid; validFrom=$from; validUntil=$until"
    }
}
