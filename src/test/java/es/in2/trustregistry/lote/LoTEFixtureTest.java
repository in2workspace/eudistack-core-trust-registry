package es.in2.trustregistry.lote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the shape of a provisioned private list. The fixtures are the reference for what the
 * configuration looks like, so a change that breaks them is a change to the contract with
 * whoever produces the file, not an incidental test failure.
 */
class LoTEFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WALLET_LIST = "eudistack-wallet-providers-lote.json";
    private static final String RELYING_LIST = "eudistack-relying-parties-lote.json";
    private static final String EAA_LIST = "eudistack-eaa-providers-lote.json";

    private static JsonNode fixture(String name) throws Exception {
        try (InputStream in = LoTEFixtureTest.class.getResourceAsStream("/fixtures/lote/" + name)) {
            assertThat(in).as("fixture %s must exist", name).isNotNull();
            return MAPPER.readTree(in);
        }
    }

    private static JsonNode firstService(JsonNode list) {
        return list.path("LoTE").path("TrustedEntitiesList").get(0)
                .path("TrustedEntityServices").get(0).path("ServiceInformation");
    }

    @ParameterizedTest
    @ValueSource(strings = {WALLET_LIST, RELYING_LIST, EAA_LIST})
    void fixture_AnyProvidedList_CarriesTheMandatorySchemeInformation(String name) throws Exception {
        // Act
        JsonNode scheme = fixture(name).path("LoTE").path("ListAndSchemeInformation");

        // Assert: the fields TS 119 602 marks as required.
        assertThat(scheme.path("LoTEVersionIdentifier").asInt()).isEqualTo(1);
        assertThat(scheme.path("LoTESequenceNumber").asInt()).isPositive();
        assertThat(scheme.path("SchemeOperatorName")).isNotEmpty();
        assertThat(scheme.path("ListIssueDateTime").asText()).isNotBlank();
        assertThat(scheme.path("NextUpdate").asText()).isNotBlank();
    }

    @ParameterizedTest
    @ValueSource(strings = {WALLET_LIST, RELYING_LIST, EAA_LIST})
    void fixture_AnyProvidedList_OmitsServiceStatus(String name) throws Exception {
        // Act
        JsonNode service = firstService(fixture(name));

        // Assert: in the EU profiles membership in the list is the status, and the reference
        // implementation rejects a list that carries one. The absence is deliberate.
        assertThat(service.has("ServiceStatus")).isFalse();
        assertThat(service.has("StatusStartingTime")).isFalse();
    }

    private static X509Certificate certificateOf(JsonNode identity) throws Exception {
        byte[] der = Base64.getDecoder().decode(identity.path("X509Certificates").get(0).path("val").asText());
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static X509Certificate leaf(String name) throws Exception {
        try (InputStream in = LoTEFixtureTest.class.getResourceAsStream("/fixtures/lote/leaf/" + name)) {
            assertThat(in).as("leaf %s must exist", name).isNotNull();
            byte[] der = Base64.getDecoder().decode(new String(in.readAllBytes()).strip());
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {WALLET_LIST, RELYING_LIST, EAA_LIST})
    void fixture_AnyProvidedList_HoldsACertificateAuthorityAsTheTrustAnchor(String name) throws Exception {
        // Act
        X509Certificate anchor = certificateOf(firstService(fixture(name)).path("ServiceDigitalIdentity"));

        // Assert: HAIP forbids the trust anchor from travelling inside the x5c of a signed
        // credential or of signed metadata, so the list has to carry it out of band — and it has
        // to be the CA, not the operational certificate. getBasicConstraints() >= 0 means CA.
        assertThat(anchor.getBasicConstraints())
                .as("the listed certificate must be a CA, not an end-entity certificate")
                .isGreaterThanOrEqualTo(0);
        assertThat(anchor.getKeyUsage()[5]).as("keyCertSign must be allowed").isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {WALLET_LIST, RELYING_LIST, EAA_LIST})
    void fixture_AnyProvidedList_DeclaresTheSubjectOfTheCertificateItCarries(String name) throws Exception {
        // Arrange
        JsonNode identity = firstService(fixture(name)).path("ServiceDigitalIdentity");

        // Act
        X509Certificate anchor = certificateOf(identity);

        // Assert: the declared subject and the embedded certificate must not drift apart, and both
        // must carry OID 2.5.4.97 — the key the registry resolves trust by.
        assertThat(anchor.getSubjectX500Principal().getName()).contains("2.5.4.97");
        assertThat(identity.path("X509SubjectNames").get(0).asText()).contains("2.5.4.97");
    }

    @ParameterizedTest
    @CsvSource({
            WALLET_LIST + ",wallet-provider.b64",
            RELYING_LIST + ",relying-party.b64",
            EAA_LIST + ",issuer.b64",
    })
    void operationalCertificate_OfAListedEntity_ChainsToTheAnchorInItsList(String list, String leafName)
            throws Exception {
        // Arrange
        X509Certificate anchor = certificateOf(firstService(fixture(list)).path("ServiceDigitalIdentity"));
        X509Certificate operational = leaf(leafName);

        // Act & Assert: this is the whole point of listing the anchor. The operational certificate
        // is what travels in the x5c; it must verify against the key the list published, and it
        // must not be self-signed, which HAIP forbids.
        operational.verify(anchor.getPublicKey());
        assertThat(operational.getIssuerX500Principal()).isEqualTo(anchor.getSubjectX500Principal());
        assertThat(operational.getSubjectX500Principal()).isNotEqualTo(operational.getIssuerX500Principal());
    }

    @Test
    void fixture_SignedAsAJws_IsVerifiableAndKeepsTheListIntact() throws Exception {
        // Arrange: the real artefact is uploaded signed, so this is the shape the loader consumes.
        ECKey schemeOperatorKey = new ECKeyGenerator(Curve.P_256).keyID("scheme-operator").generate();
        JsonNode list = fixture(RELYING_LIST);

        // Act
        JWSObject jws = new JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .type(new JOSEObjectType("lote+jwt"))
                        .keyID(schemeOperatorKey.getKeyID())
                        .build(),
                new Payload(MAPPER.writeValueAsString(list)));
        jws.sign(new ECDSASigner(schemeOperatorKey));
        JWSObject parsed = JWSObject.parse(jws.serialize());

        // Assert
        assertThat(parsed.verify(new ECDSAVerifier(schemeOperatorKey.toPublicJWK()))).isTrue();
        assertThat(MAPPER.readTree(parsed.getPayload().toString())
                .path("LoTE").path("TrustedEntitiesList").get(0)
                .path("TrustedEntityInformation").path("TETradeName").get(0).path("value").asText())
                .isEqualTo("VATES-B12345678");
    }
}
