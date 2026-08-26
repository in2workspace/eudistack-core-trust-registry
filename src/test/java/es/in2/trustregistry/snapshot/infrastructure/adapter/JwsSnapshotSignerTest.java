package es.in2.trustregistry.snapshot.infrastructure.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import es.in2.trustregistry.snapshot.domain.model.TrustSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.ParseException;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwsSnapshotSignerTest {

    private static final TrustSnapshot SNAPSHOT = new TrustSnapshot(
            "sandbox", 7L, Instant.parse("2026-08-25T10:00:00Z"), 86400, List.of(), List.of());

    private JwsSnapshotSigner signer;

    @BeforeEach
    void setUp() throws JOSEException {
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        signer = new JwsSnapshotSigner(objectMapper);
        signer.generateEphemeralKey();
    }

    @Test
    void sign_ValidSnapshot_ProducesAJwsVerifiableWithThePublishedPublicKey() throws ParseException, JOSEException {
        // Act
        String serialized = signer.sign(SNAPSHOT);

        // Assert: this is the whole point of the snapshot — a consumer with only the
        // public key, and no connectivity to the registry, must be able to verify it.
        JWSObject jws = JWSObject.parse(serialized);
        assertThat(jws.verify(new ECDSAVerifier(signer.publicKey()))).isTrue();
    }

    @Test
    void sign_ValidSnapshot_CarriesTheKeyIdAndTheSnapshotMediaType() throws ParseException {
        // Act
        JWSObject jws = JWSObject.parse(signer.sign(SNAPSHOT));

        // Assert
        assertThat(jws.getHeader().getKeyID()).isEqualTo("trust-registry-dev");
        assertThat(jws.getHeader().getType().toString()).isEqualTo("trust-snapshot+jwt");
    }

    @Test
    void sign_ValidSnapshot_PayloadCarriesTheTenantAndTheVersion() throws ParseException {
        // Act
        JWSObject jws = JWSObject.parse(signer.sign(SNAPSHOT));

        // Assert
        assertThat(jws.getPayload().toJSONObject())
                .containsEntry("tenantId", "sandbox")
                .containsEntry("version", 7L);
    }

    @Test
    void publicKey_AfterKeyGeneration_DoesNotExposeThePrivatePart() {
        // Act & Assert: the signing material must never leave the service.
        assertThat(signer.publicKey().isPrivate()).isFalse();
    }
}
