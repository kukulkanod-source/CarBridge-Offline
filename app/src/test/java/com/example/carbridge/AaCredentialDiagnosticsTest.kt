package com.example.carbridge

import com.example.carbridge.link.AaPhoneCredentialSummary
import net.prototux.aare.aap.cert.SelfSignedCertificate
import org.junit.Assert.*
import org.junit.Test

class AaCredentialDiagnosticsTest {
    @Test fun publicCredentialSummarySurvivesTheActualLogFilter() {
        val cert = SelfSignedCertificate.generate("DO NOT LOG THIS SUBJECT").certificate
        for (source in AaPhoneCredentialSummary.Source.values()) {
            val text = AaPhoneCredentialSummary.format(cert, 1, source, cert.notBefore.toInstant())
            assertEquals(text, DiagnosticMessageFilter.clean(text))
            assertTrue(text.contains("timeValid=true"))
            assertTrue(text.contains("chainLength=1"))
            assertTrue(Regex("sha256=[0-9a-f]{64};").containsMatchIn(text))
            assertFalse(text.contains("DO NOT LOG"))
            assertTrue(AaPhoneCredentialSummary.format(cert, 1, source, cert.notAfter.toInstant().plusSeconds(1))
                .contains("timeValid=false"))
        }
    }

    @Test fun secretFilteringIsNotWeakened() {
        for (text in listOf("pairing code 123456", "код сопряжения 123456", "adbkey data",
            "privateKey data", "-----BEGIN CERTIFICATE-----", "AA phone identity; secret")) {
            assertEquals("[sensitive authentication detail omitted]", DiagnosticMessageFilter.clean(text))
        }
        assertEquals("line one  line two", DiagnosticMessageFilter.clean("line one\r\nline two"))
    }
}
