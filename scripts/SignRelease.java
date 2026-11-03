import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SignRelease {
    private static final Set<String> SETTINGS = Set.of(
        "SEASONVAR_SIGNING_KEYSTORE_BASE64",
        "SEASONVAR_SIGNING_STORE_PASSWORD",
        "SEASONVAR_SIGNING_KEY_PASSWORD",
        "SEASONVAR_SIGNING_KEY_ALIAS"
    );

    private static Map<String, String> readSettings() throws Exception {
        Path path = Path.of("secrets.env");
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path)) {
            String text = line.strip().replaceFirst("^export\\s+", "");
            int separator = text.indexOf('=');
            if (separator < 0) continue;
            String name = text.substring(0, separator).strip();
            if (!SETTINGS.contains(name)) continue;
            String value = text.substring(separator + 1).strip();
            if (value.length() >= 2 && (
                (value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))
            )) value = value.substring(1, value.length() - 1);
            if (value.isEmpty() || values.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("Empty or duplicate signing setting");
            }
        }
        if (!values.keySet().equals(SETTINGS)) {
            throw new IllegalArgumentException("Missing signing settings");
        }
        return values;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: SignRelease.java unsigned.apk signed.apk");
            System.exit(1);
        }
        Path input = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (input.equals(output)) {
            System.err.println("Input and output APK paths must differ.");
            System.exit(1);
        }
        String stage = "reading project secrets.env";
        try {
            Files.deleteIfExists(output);
            var values = readSettings();
            stage = "loading the signing key";
            var store = KeyStore.getInstance("PKCS12");
            store.load(new ByteArrayInputStream(Base64.getDecoder().decode(
                values.get("SEASONVAR_SIGNING_KEYSTORE_BASE64"))),
                values.get("SEASONVAR_SIGNING_STORE_PASSWORD").toCharArray());
            String alias = values.get("SEASONVAR_SIGNING_KEY_ALIAS");
            var key = (PrivateKey) store.getKey(alias,
                values.get("SEASONVAR_SIGNING_KEY_PASSWORD").toCharArray());
            var certificates = new ArrayList<X509Certificate>();
            for (var certificate : store.getCertificateChain(alias)) {
                certificates.add((X509Certificate) certificate);
            }
            var signer = new ApkSigner.SignerConfig.Builder("seasonvar", key, certificates).build();
            stage = "signing the release APK";
            new ApkSigner.Builder(List.of(signer))
                .setInputApk(input.toFile())
                .setOutputApk(output.toFile())
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setDebuggableApkPermitted(false)
                .build().sign();
            stage = "verifying the APK signature";
            var result = new ApkVerifier.Builder(output.toFile()).build().verify();
            if (!result.isVerified() || !result.isVerifiedUsingV2Scheme() ||
                !result.isVerifiedUsingV3Scheme() ||
                !result.getSignerCertificates().equals(List.of(certificates.get(0)))) {
                throw new IllegalStateException("APK signature verification failed");
            }
            System.out.println("Release APK signed and verified (v2/v3): " + args[1]);
        } catch (Exception error) {
            Files.deleteIfExists(output);
            System.err.println("Release signing failed while " + stage + ".");
            System.exit(1);
        }
    }
}
