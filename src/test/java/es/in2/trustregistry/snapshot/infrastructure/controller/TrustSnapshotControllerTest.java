package es.in2.trustregistry.snapshot.infrastructure.controller;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;
import es.in2.trustregistry.shared.infrastructure.config.TrustRegistryProperties;
import es.in2.trustregistry.snapshot.application.TrustSnapshotService;
import es.in2.trustregistry.snapshot.domain.model.PublicVerificationKey;
import es.in2.trustregistry.snapshot.domain.model.PublishedSnapshot;
import es.in2.trustregistry.snapshot.domain.model.SnapshotFingerprint;
import es.in2.trustregistry.snapshot.domain.model.TrustProfile;
import es.in2.trustregistry.snapshot.domain.port.SnapshotVerificationMaterialPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TrustSnapshotController.class)
class TrustSnapshotControllerTest {

    private static final String TENANT = "sandbox";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TrustSnapshotService service;

    @MockitoBean
    private SnapshotVerificationMaterialPort verificationMaterial;

    @MockitoBean
    private TrustRegistryProperties properties;

    @Test
    void signedSnapshot_TenantHeaderPresent_ReturnsTheCompactJwsWithAnETag() throws Exception {
        // Arrange
        when(service.publishFor(TENANT)).thenReturn(published(12L, "header.payload.signature"));

        // Act & Assert
        mockMvc.perform(get("/trust/v1/snapshot").header("X-Tenant", TENANT))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"12\""))
                .andExpect(content().string("header.payload.signature"));
    }

    @Test
    void signedSnapshot_TenantHeaderMissing_RejectsWithBadRequest() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/trust/v1/snapshot"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signedSnapshot_TenantHeaderBlank_RejectsWithBadRequest() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/trust/v1/snapshot").header("X-Tenant", "   "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signedSnapshot_IfNoneMatchMatchesCurrentVersion_ReturnsNotModifiedWithNoBody() throws Exception {
        // Arrange
        when(service.publishFor(TENANT)).thenReturn(published(12L, "header.payload.signature"));

        // Act & Assert
        mockMvc.perform(get("/trust/v1/snapshot")
                        .header("X-Tenant", TENANT)
                        .header("If-None-Match", "\"12\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", "\"12\""))
                .andExpect(content().string(""));
    }

    @Test
    void signedSnapshot_IfNoneMatchIsAnUnknownVersion_ReturnsTheFullBody() throws Exception {
        // Arrange — ES-04: a version the registry does not recognise as current is not-vigente,
        // so the consumer must be allowed to download the full snapshot, never rejected.
        when(service.publishFor(TENANT)).thenReturn(published(12L, "header.payload.signature"));

        // Act & Assert
        mockMvc.perform(get("/trust/v1/snapshot")
                        .header("X-Tenant", TENANT)
                        .header("If-None-Match", "\"999\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"12\""))
                .andExpect(content().string("header.payload.signature"));
    }

    @Test
    void plainSnapshot_DevelopmentProfile_ReturnsTheDecodedSignedPayload() throws Exception {
        // Arrange
        when(properties.trustProfile()).thenReturn(TrustProfile.DEVELOPMENT);
        when(service.buildSigned(TENANT)).thenReturn(signedSnapshotFixture());

        // Act & Assert
        mockMvc.perform(get("/trust/v1/snapshot/plain").header("X-Tenant", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void plainSnapshot_NonDevelopmentProfile_RespondsNotFound() throws Exception {
        // Arrange
        when(properties.trustProfile()).thenReturn(TrustProfile.PRODUCTION);

        // Act & Assert
        mockMvc.perform(get("/trust/v1/snapshot/plain").header("X-Tenant", TENANT))
                .andExpect(status().isNotFound());
    }

    @Test
    void jwks_Always_PublishesTheVerificationKeyWithoutThePrivatePart() throws Exception {
        // Arrange
        when(verificationMaterial.verificationMaterial())
                .thenReturn(List.of(new PublicVerificationKey("trust-registry-dev", "P-256", "x-coord", "y-coord")));

        // Act & Assert
        mockMvc.perform(get("/trust/v1/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value("trust-registry-dev"))
                .andExpect(jsonPath("$.keys[0].kty").value("EC"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    private static PublishedSnapshot published(long version, String signedDocument) {
        SnapshotFingerprint fingerprint =
                SnapshotFingerprint.of(TrustAnchorSet.neverSynced(), List.of(), TrustProfile.PRODUCTION);
        return new PublishedSnapshot(TENANT, version, fingerprint, signedDocument);
    }

    /** A real, parseable JWS whose payload is the JSON a consumer of {@code /snapshot/plain} expects. */
    private static String signedSnapshotFixture() throws Exception {
        ECKey signingKey = new ECKeyGenerator(Curve.P_256).keyID("trust-registry-test").generate();
        JWSSigner signer = new ECDSASigner(signingKey);
        JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                .type(new JOSEObjectType("trust-snapshot+jwt"))
                .keyID(signingKey.getKeyID())
                .build();
        String payload = "{\"tenantId\":\"" + TENANT + "\",\"version\":3}";
        JWSObject jws = new JWSObject(header, new Payload(payload));
        jws.sign(signer);
        return jws.serialize();
    }
}
