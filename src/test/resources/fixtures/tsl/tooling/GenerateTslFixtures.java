import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.enumerations.SignaturePackaging;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.SignatureValue;
import eu.europa.esig.dss.model.ToBeSigned;
import eu.europa.esig.dss.spi.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.token.DSSPrivateKeyEntry;
import eu.europa.esig.dss.token.Pkcs12SignatureToken;
import eu.europa.esig.dss.xades.XAdESSignatureParameters;
import eu.europa.esig.dss.xades.signature.XAdESService;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Regenerates the signed TSL/LOTL fixtures for EUD-227 (task 13).
 *
 * <p>Not a JUnit test and not part of the application's compiled classes: invoked only by
 * {@code regenerate-fixtures.sh}, which compiles and runs this single file against the
 * {@code test} source set's runtime classpath (already carrying {@code dss-xades} — see
 * {@code build.gradle}). Kept as one script-style file, mirroring the {@code dss-config}
 * regeneration pattern (a documented one-off command, not permanent production/test code).
 *
 * <p>Every fixture is signed with the throwaway keystore at
 * {@code fixtures/tsl/keystore/tsl-test-signing-keystore.p12} (alias {@code tsl-test-ca}) —
 * never the real production {@code oj-keystore.p12} (task 7) nor the unrelated
 * {@code dss-config} keystore used only to exercise {@code DssTrustListJobConfigTest}'s
 * ES-01 fail-fast path.
 *
 * <p>Signing follows DSS's own canonical minimal example
 * ({@code dss-cookbook}'s {@code SignXmlXadesBTest}): {@link XAdESService} with
 * {@link SignatureLevel#XAdES_BASELINE_B}, {@link SignaturePackaging#ENVELOPED}, SHA-256 —
 * the same shape as the real {@code ds:Signature}/{@code xades:QualifyingProperties} block
 * found in the genuine {@code eu-lotl.xml} DSS ships in its own test resources.
 */
public final class GenerateTslFixtures {

    private static final Path FIXTURES_DIR = Path.of("src/test/resources/fixtures/tsl");
    private static final Path TEMPLATES_DIR = FIXTURES_DIR.resolve("templates");
    private static final Path KEYSTORE_PATH = FIXTURES_DIR.resolve("keystore/tsl-test-signing-keystore.p12");
    private static final char[] KEYSTORE_PASSWORD = "tsl-test-password".toCharArray();
    private static final String CA_ALIAS = "tsl-test-ca";

    // Fixed points in time so regeneration is deterministic and reviewable in diffs.
    private static final Instant ISSUE_DATE = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant FUTURE_NEXT_UPDATE = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant PAST_NEXT_UPDATE = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant STATUS_STARTING_TIME = Instant.parse("2025-01-01T00:00:00Z");

    // Fixed URLs the container test (task 15) will bind the fixtures to.
    private static final String TL_A_URL = "http://tsl-fixtures/tl-national-a-valid.xml";
    private static final String TL_B_URL = "http://tsl-fixtures/tl-national-b-valid.xml";

    private final Pkcs12SignatureToken signingToken;
    private final DSSPrivateKeyEntry caKey;
    private final String caCertificateBase64;

    public static void main(String[] args) throws Exception {
        GenerateTslFixtures generator = new GenerateTslFixtures();
        try {
            generator.generateAll();
        } finally {
            generator.close();
        }
        System.out.println("Fixtures regenerated under " + FIXTURES_DIR.toAbsolutePath());
    }

    private GenerateTslFixtures() throws IOException {
        KeyStore.PasswordProtection password = new KeyStore.PasswordProtection(KEYSTORE_PASSWORD);
        this.signingToken = new Pkcs12SignatureToken(KEYSTORE_PATH.toFile(), password);
        this.caKey = signingToken.getKey(CA_ALIAS, password);
        this.caCertificateBase64 = toBase64(caKey.getCertificate().getEncoded());
    }

    private void close() {
        signingToken.close();
    }

    private static String toBase64(byte[] derEncoded) {
        return Base64.getEncoder().encodeToString(derEncoded);
    }

    private void generateAll() throws IOException {
        // Two plain valid national TLs (AC-01), each with one granted service.
        String serviceAaCertificate = leafCertificateBase64("service-aa");
        signAndWrite("tl-national-a-valid.xml", Map.of(
                "{{ISSUE_DATE}}", ISSUE_DATE.toString(),
                "{{NEXT_UPDATE}}", FUTURE_NEXT_UPDATE.toString(),
                "{{SERVICE_CERTIFICATE_BASE64}}", serviceAaCertificate,
                "{{STATUS_STARTING_TIME}}", STATUS_STARTING_TIME.toString()
        ));

        String serviceBbCertificate = leafCertificateBase64("service-bb");
        signAndWrite("tl-national-b-valid.xml", Map.of(
                "{{ISSUE_DATE}}", ISSUE_DATE.toString(),
                "{{NEXT_UPDATE}}", FUTURE_NEXT_UPDATE.toString(),
                "{{SERVICE_CERTIFICATE_BASE64}}", serviceBbCertificate,
                "{{STATUS_STARTING_TIME}}", STATUS_STARTING_TIME.toString()
        ));

        // LOTL pointing at both valid national TLs (AC-01).
        signAndWrite("lotl-valid.xml", Map.of(
                "{{ISSUE_DATE}}", ISSUE_DATE.toString(),
                "{{NEXT_UPDATE}}", FUTURE_NEXT_UPDATE.toString(),
                "{{TL_NATIONAL_A_URL}}", TL_A_URL,
                "{{TL_NATIONAL_B_URL}}", TL_B_URL,
                "{{TEST_CA_CERTIFICATE_BASE64}}", caCertificateBase64
        ));

        // Same LOTL content, but with a past NextUpdate (EC-02) — its own output file so
        // AC-01's lotl-valid.xml is untouched.
        signAndWrite("lotl-valid.xml", "lotl-stale-next-update.xml", Map.of(
                "{{ISSUE_DATE}}", ISSUE_DATE.toString(),
                "{{NEXT_UPDATE}}", PAST_NEXT_UPDATE.toString(),
                "{{TL_NATIONAL_A_URL}}", TL_A_URL,
                "{{TL_NATIONAL_B_URL}}", TL_B_URL,
                "{{TEST_CA_CERTIFICATE_BASE64}}", caCertificateBase64
        ));

        // National TL with a past NextUpdate (EC-02's TL-level counterpart, defence in depth
        // for task 15's scenarios).
        signAndWrite("tl-national-stale-next-update.xml", Map.of(
                "{{ISSUE_DATE}}", ISSUE_DATE.toString(),
                "{{NEXT_UPDATE}}", PAST_NEXT_UPDATE.toString(),
                "{{SERVICE_CERTIFICATE_BASE64}}", leafCertificateBase64("service-dd"),
                "{{STATUS_STARTING_TIME}}", STATUS_STARTING_TIME.toString()
        ));

        // National TL with three services in three different statuses (AC-03): granted,
        // withdrawn, and the supervisionrevoked substitution for the literal "suspended" ask
        // (see fixtures/tsl/README.md for the full resolution).
        signAndWrite("tl-national-three-statuses.xml", Map.of(
                "{{ISSUE_DATE}}", ISSUE_DATE.toString(),
                "{{NEXT_UPDATE}}", FUTURE_NEXT_UPDATE.toString(),
                "{{GRANTED_SERVICE_CERTIFICATE_BASE64}}", leafCertificateBase64("service-granted-cc"),
                "{{WITHDRAWN_SERVICE_CERTIFICATE_BASE64}}", leafCertificateBase64("service-withdrawn-cc"),
                "{{LEGACY_STATUS_SERVICE_CERTIFICATE_BASE64}}", leafCertificateBase64("service-legacy-cc"),
                "{{STATUS_STARTING_TIME}}", STATUS_STARTING_TIME.toString()
        ));

        // Tampered national TL (AC-02): sign the valid content, then flip one visible
        // character in a signed element so the digest no longer matches. Byte-level edit
        // happens after signing, never before, so the signature itself is well-formed and
        // only the content/digest mismatch is what makes DSS reject it.
        tamperAndWrite("tl-national-a-valid.xml", "tl-national-tampered.xml", Map.of(
                "{{ISSUE_DATE}}", ISSUE_DATE.toString(),
                "{{NEXT_UPDATE}}", FUTURE_NEXT_UPDATE.toString(),
                "{{SERVICE_CERTIFICATE_BASE64}}", serviceAaCertificate,
                "{{STATUS_STARTING_TIME}}", STATUS_STARTING_TIME.toString()
        ));

        // Tampered LOTL (ES-02): same technique, applied to the LOTL itself.
        tamperAndWrite("lotl-valid.xml", "lotl-tampered.xml", Map.of(
                "{{ISSUE_DATE}}", ISSUE_DATE.toString(),
                "{{NEXT_UPDATE}}", FUTURE_NEXT_UPDATE.toString(),
                "{{TL_NATIONAL_A_URL}}", TL_A_URL,
                "{{TL_NATIONAL_B_URL}}", TL_B_URL,
                "{{TEST_CA_CERTIFICATE_BASE64}}", caCertificateBase64
        ));
    }

    private String leafCertificateBase64(String alias) {
        KeyStore.PasswordProtection password = new KeyStore.PasswordProtection(KEYSTORE_PASSWORD);
        DSSPrivateKeyEntry entry = signingToken.getKey(alias, password);
        return toBase64(entry.getCertificate().getEncoded());
    }

    private void signAndWrite(String templateName, Map<String, String> substitutions) throws IOException {
        signAndWrite(templateName, templateName, substitutions);
    }

    private void signAndWrite(String templateName, String outputName, Map<String, String> substitutions)
            throws IOException {
        DSSDocument toSign = fillTemplate(templateName, substitutions);
        DSSDocument signed = sign(toSign);
        writeFixture(outputName, signed);
    }

    private void tamperAndWrite(String templateName, String outputName, Map<String, String> substitutions)
            throws IOException {
        DSSDocument toSign = fillTemplate(templateName, substitutions);
        DSSDocument signed = sign(toSign);
        String signedXml = new String(signed.openStream().readAllBytes(), StandardCharsets.UTF_8);
        // Flip the territory content of the first TSP/TL after signing: a byte-level edit to
        // already-signed content, so the enveloped signature parses fine but its digest no
        // longer matches (AC-02 / ES-02's "signature does not verify", not "malformed XML").
        String tampered = signedXml.replaceFirst(
                "(<SchemeTerritory>)([A-Z]{2})(</SchemeTerritory>)", "$1ZZ$3");
        if (tampered.equals(signedXml)) {
            throw new IllegalStateException(
                    "Tampering marker <SchemeTerritory> not found in " + templateName
                            + "; refusing to write a fixture that was not actually tampered");
        }
        writeFixture(outputName, new InMemoryDocument(tampered.getBytes(StandardCharsets.UTF_8)));
    }

    private DSSDocument fillTemplate(String templateName, Map<String, String> substitutions) throws IOException {
        String content = Files.readString(TEMPLATES_DIR.resolve(templateName), StandardCharsets.UTF_8);
        for (Map.Entry<String, String> substitution : substitutions.entrySet()) {
            content = content.replace(substitution.getKey(), substitution.getValue());
        }
        if (content.contains("{{")) {
            throw new IllegalStateException(
                    "Template " + templateName + " still has unresolved placeholders after substitution");
        }
        return new InMemoryDocument(content.getBytes(StandardCharsets.UTF_8), templateName);
    }

    private DSSDocument sign(DSSDocument toSignDocument) {
        XAdESSignatureParameters parameters = new XAdESSignatureParameters();
        parameters.setSignatureLevel(SignatureLevel.XAdES_BASELINE_B);
        parameters.setSignaturePackaging(SignaturePackaging.ENVELOPED);
        parameters.setDigestAlgorithm(DigestAlgorithm.SHA256);
        parameters.setSigningCertificate(caKey.getCertificate());
        parameters.setCertificateChain(caKey.getCertificateChain());

        CommonCertificateVerifier certificateVerifier = new CommonCertificateVerifier();
        XAdESService service = new XAdESService(certificateVerifier);

        ToBeSigned dataToSign = service.getDataToSign(toSignDocument, parameters);
        SignatureValue signatureValue = signingToken.sign(dataToSign, parameters.getDigestAlgorithm(), caKey);
        return service.signDocument(toSignDocument, parameters, signatureValue);
    }

    private void writeFixture(String fileName, DSSDocument document) throws IOException {
        Path target = FIXTURES_DIR.resolve(fileName);
        try (var inputStream = document.openStream()) {
            Files.write(target, inputStream.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed writing fixture " + fileName, e);
        }
        System.out.println("Wrote " + target);
    }
}
