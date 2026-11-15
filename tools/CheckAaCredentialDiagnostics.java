import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;

/** Runs the Android-independent checks against already compiled Kotlin classes. */
class CheckAaCredentialDiagnostics {
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        // Reflection avoids this machine's javac classpath enumeration failure.
        var generator = Class.forName("net.prototux.aare.aap.cert.SelfSignedCertificate");
        var generated = generator.getMethod("generate", String.class, int.class, SecureRandom.class)
            .invoke(generator.getField("INSTANCE").get(null), "DO NOT LOG THIS SUBJECT", 1, new SecureRandom());
        var cert = (X509Certificate) generated.getClass().getMethod("getCertificate").invoke(generated);
        var summary = Class.forName("com.example.carbridge.link.AaPhoneCredentialSummary");
        var sourceType = Class.forName("com.example.carbridge.link.AaPhoneCredentialSummary$Source");
        var format = summary.getMethod("format", X509Certificate.class, int.class, sourceType, Instant.class);
        var summaryObject = summary.getField("INSTANCE").get(null);
        var filter = Class.forName("com.example.carbridge.DiagnosticMessageFilter");
        var clean = filter.getMethod("clean", String.class);
        var filterObject = filter.getField("INSTANCE").get(null);
        for (var source : sourceType.getEnumConstants()) {
            var text = (String) format.invoke(summaryObject, cert, 1, source, cert.getNotBefore().toInstant());
            require(text.equals(clean.invoke(filterObject, text)));
            require(text.contains("timeValid=true") && text.contains("chainLength=1"));
            require(text.matches(".*sha256=[0-9a-f]{64};.*"));
            require(!text.contains("DO NOT LOG"));
            require(((String) format.invoke(summaryObject, cert, 1, source,
                cert.getNotAfter().toInstant().plusSeconds(1))).contains("timeValid=false"));
        }
        for (var text : new String[]{"pairing code 123456", "код сопряжения 123456", "adbkey data",
                "privateKey data", "-----BEGIN CERTIFICATE-----", "AA phone identity; secret"}) {
            require(clean.invoke(filterObject, text).equals("[sensitive authentication detail omitted]"));
        }
        require(clean.invoke(filterObject, "line one\r\nline two").equals("line one  line two"));
        System.out.println("AA public credential diagnostics and secret-filter checks passed");
    }
}
