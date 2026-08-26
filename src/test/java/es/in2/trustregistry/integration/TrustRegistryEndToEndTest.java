package es.in2.trustregistry.integration;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import es.in2.trustregistry.entities.domain.model.EntityRole;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full stack through the real HTTP layer: no mocks, no slices. Proves the two
 * properties the whole design rests on — a consumer can verify a published
 * snapshot offline with nothing but the JWKS, and trust never leaks across
 * tenants.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrustRegistryEndToEndTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String ORG_ID = "VATES-B12345678";

    @Autowired
    private TestRestTemplate rest;

    private static HttpEntity<TrustedEntity> request(String tenantId, TrustedEntity body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant", tenantId);
        return new HttpEntity<>(body, headers);
    }

    private static HttpEntity<Void> headers(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant", tenantId);
        return new HttpEntity<>(headers);
    }

    private void register(String tenantId, String organizationIdentifier, EntityRole role) {
        TrustedEntity entity = new TrustedEntity(tenantId, organizationIdentifier, "Acme SL",
                Set.of(role), "pem", Instant.now().minusSeconds(60), null);
        ResponseEntity<TrustedEntity> response =
                rest.exchange("/trust/v1/entities", HttpMethod.POST, request(tenantId, entity), TrustedEntity.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void publishedSnapshot_VerifiedWithThePublishedJwks_IsAuthentic() throws Exception {
        // Arrange
        register(TENANT_A, ORG_ID, EntityRole.RELYING_PARTY);

        // Act
        String jwks = rest.getForObject("/trust/v1/jwks", String.class);
        String snapshot = rest.exchange("/trust/v1/snapshot", HttpMethod.GET, headers(TENANT_A), String.class).getBody();

        // Assert: this is exactly what an offline consumer does — parse the key
        // it cached earlier and verify the artefact without calling back.
        ECKey key = (ECKey) JWKSet.parse(jwks).getKeys().getFirst();
        JWSObject jws = JWSObject.parse(snapshot);
        assertThat(jws.verify(new ECDSAVerifier(key))).isTrue();
        assertThat(jws.getPayload().toJSONObject()).containsEntry("tenantId", TENANT_A);
    }

    @Test
    void snapshot_EntityRegisteredInAnotherTenant_IsNotIncluded() throws Exception {
        // Arrange
        register(TENANT_A, "VATES-ONLY-IN-A", EntityRole.RELYING_PARTY);

        // Act
        String snapshot = rest.exchange("/trust/v1/snapshot", HttpMethod.GET, headers(TENANT_B), String.class).getBody();

        // Assert
        assertThat(JWSObject.parse(snapshot).getPayload().toString()).doesNotContain("VATES-ONLY-IN-A");
    }

    @Test
    void trustCheck_OrganizationRegisteredInAnotherTenant_IsNotTrusted() {
        // Arrange
        register(TENANT_A, ORG_ID, EntityRole.RELYING_PARTY);

        // Act
        Boolean fromOtherTenant = rest.exchange(
                "/trust/v1/entities/{id}/trusted?role=RELYING_PARTY", HttpMethod.GET,
                headers(TENANT_B), Boolean.class, ORG_ID).getBody();
        Boolean fromOwnTenant = rest.exchange(
                "/trust/v1/entities/{id}/trusted?role=RELYING_PARTY", HttpMethod.GET,
                headers(TENANT_A), Boolean.class, ORG_ID).getBody();

        // Assert
        assertThat(fromOtherTenant).isFalse();
        assertThat(fromOwnTenant).isTrue();
    }

    @Test
    void trustCheck_RoleNotRegisteredForTheOrganization_IsNotTrusted() {
        // Arrange
        register(TENANT_A, "VATES-WALLET-ONLY", EntityRole.WALLET_PROVIDER);

        // Act
        Boolean trusted = rest.exchange(
                "/trust/v1/entities/{id}/trusted?role=RELYING_PARTY", HttpMethod.GET,
                headers(TENANT_A), Boolean.class, "VATES-WALLET-ONLY").getBody();

        // Assert: fail closed — registered, but not for this role.
        assertThat(trusted).isFalse();
    }
}
