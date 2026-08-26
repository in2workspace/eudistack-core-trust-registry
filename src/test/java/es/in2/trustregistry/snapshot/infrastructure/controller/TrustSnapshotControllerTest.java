package es.in2.trustregistry.snapshot.infrastructure.controller;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import es.in2.trustregistry.snapshot.application.TrustSnapshotService;
import es.in2.trustregistry.snapshot.domain.model.TrustSnapshot;
import es.in2.trustregistry.snapshot.infrastructure.adapter.JwsSnapshotSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
    private JwsSnapshotSigner signer;

    @Test
    void signedSnapshot_TenantHeaderPresent_ReturnsTheCompactJws() throws Exception {
        // Arrange
        when(service.buildSigned(TENANT)).thenReturn("header.payload.signature");

        // Act & Assert
        mockMvc.perform(get("/trust/v1/snapshot").header("X-Tenant", TENANT))
                .andExpect(status().isOk())
                .andExpect(content().string("header.payload.signature"));
    }

    @Test
    void plainSnapshot_TenantHeaderPresent_ReturnsTheUnsignedSnapshot() throws Exception {
        // Arrange
        when(service.build(TENANT)).thenReturn(new TrustSnapshot(
                TENANT, 3L, Instant.parse("2026-08-25T10:00:00Z"), 86400, List.of(), List.of()));

        // Act & Assert
        mockMvc.perform(get("/trust/v1/snapshot/plain").header("X-Tenant", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT))
                .andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void jwks_Always_PublishesTheVerificationKeyWithoutThePrivatePart() throws Exception {
        // Arrange
        ECKey key = new ECKeyGenerator(Curve.P_256).keyID("trust-registry-dev").generate();
        when(signer.publicKey()).thenReturn(key.toPublicJWK());

        // Act & Assert
        mockMvc.perform(get("/trust/v1/jwks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kid").value("trust-registry-dev"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }
}
