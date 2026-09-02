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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @ParameterizedTest
    @ValueSource(strings = {
            "ACME",              // uppercase — collides with "acme" on case-insensitive filesystems
            "-acme",             // must start with an alphanumeric, not a separator
            "acme/../other",     // classic traversal — the allowlist rejects the "/" alone; fileFor()
            "acme\\other",       // in FileSystemPublishedSnapshotRepository keeps its own denylist
            "acme with spaces",  // as defence in depth, unchanged by this fix
            "acmeé",             // non-ASCII (unicode confusable / normalisation risk)
    })
    void signedSnapshot_TenantHeaderFailsTheAllowlist_RejectsWithBadRequestWithoutCallingTheService(String invalidTenant)
            throws Exception {
        // Arrange — quality-report.md S1/TD-03: the allowlist is enforced once, here, before the
        // tenant value can reach FileSystemPublishedSnapshotRepository or any other adapter.
        //
        // Not covered here on purpose: "a..b" (consecutive dots, no slash) is syntactically valid
        // under this allowlist — "." is one of the permitted separator characters and the regex
        // does not forbid repeating it — so it is not an allowlist gap. It is still rejected
        // end-to-end because FileSystemPublishedSnapshotRepository.fileFor() denylists any
        // tenantId containing "..", the defence-in-depth check this fix deliberately keeps rather
        // than replaces; that adapter-level guard has its own test file (W3/TD-08 tracks the gap
        // in direct coverage for it, out of scope for this fix).

        // Act & Assert
        mockMvc.perform(get("/trust/v1/snapshot").header("X-Tenant", invalidTenant))
                .andExpect(status().isBadRequest());
        verify(service, never()).publishFor(invalidTenant);
    }

    @Test
    void signedSnapshot_TenantHeaderIsSixtyFiveCharacters_RejectsWithBadRequest() throws Exception {
        // Arrange — length bound: the allowlist caps at 64 characters, closing S3's unbounded-key
        // amplification alongside S1's charset/case gap.
        String tooLong = "a".repeat(65);

        // Act & Assert
        mockMvc.perform(get("/trust/v1/snapshot").header("X-Tenant", tooLong))
                .andExpect(status().isBadRequest());
        verify(service, never()).publishFor(tooLong);
    }

    @Test
    void signedSnapshot_TenantHeaderHasDotsAndHyphens_IsAcceptedByTheAllowlist() throws Exception {
        // Arrange — the allowlist must not be so strict it rejects legitimate tenant slugs that
        // use the separators it explicitly permits (._-).
        String tenant = "cgcom.demo-1";
        when(service.publishFor(tenant)).thenReturn(published(1L, "cgcom.demo-1.payload.signature"));

        // Act & Assert
        mockMvc.perform(get("/trust/v1/snapshot").header("X-Tenant", tenant))
                .andExpect(status().isOk())
                .andExpect(content().string("cgcom.demo-1.payload.signature"));
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
    void signedSnapshot_TenantHeaderIdentifiesAnUnknownTenant_StillPublishesForThatTenant() throws Exception {
        // Arrange — ES-05: a tenant the registry has no private list for is not rejected; the
        // controller forwards it to the service exactly as received (empty-but-valid publication
        // is TrustSnapshotService's responsibility, already covered by EC-02 in
        // TrustSnapshotServiceTest — this test only proves the controller does not special-case
        // or block an unrecognised tenant before reaching the service).
        String unknownTenant = "unknown-tenant";
        when(service.publishFor(unknownTenant)).thenReturn(published(1L, "unknown-tenant.payload.signature"));

        // Act & Assert
        mockMvc.perform(get("/trust/v1/snapshot").header("X-Tenant", unknownTenant))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(content().string("unknown-tenant.payload.signature"));
    }

    @Test
    void signedSnapshot_DifferentTenantHeadersAcrossRequests_EachCallsServiceWithItsOwnTenantOnly() throws Exception {
        // Arrange — ES-05, controller-level slice of "sin fuga entre tenants": the controller
        // holds no state across requests, so two successive calls with different X-Tenant
        // values must each reach the service with exactly the tenant they carried, never a
        // mix-up or a leftover from the previous request. Real content isolation between
        // tenants is proven end-to-end by TrustRegistryEndToEndTest (AC-08); this test is
        // narrower — it only proves the controller's header-to-service wiring itself.
        String tenantA = "tenant-a";
        String tenantB = "tenant-b";
        when(service.publishFor(tenantA)).thenReturn(published(1L, "tenant-a.payload.signature"));
        when(service.publishFor(tenantB)).thenReturn(published(1L, "tenant-b.payload.signature"));

        // Act
        mockMvc.perform(get("/trust/v1/snapshot").header("X-Tenant", tenantA))
                .andExpect(status().isOk())
                .andExpect(content().string("tenant-a.payload.signature"));
        mockMvc.perform(get("/trust/v1/snapshot").header("X-Tenant", tenantB))
                .andExpect(status().isOk())
                .andExpect(content().string("tenant-b.payload.signature"));

        // Assert
        verify(service).publishFor(tenantA);
        verify(service).publishFor(tenantB);
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
