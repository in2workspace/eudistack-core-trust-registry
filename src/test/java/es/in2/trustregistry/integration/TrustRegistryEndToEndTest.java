package es.in2.trustregistry.integration;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;
import es.in2.trustregistry.anchors.domain.model.TrustServiceStatus;
import es.in2.trustregistry.anchors.domain.port.TrustAnchorRepositoryPort;
import es.in2.trustregistry.entities.domain.model.EntityRole;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import es.in2.trustregistry.entities.domain.port.TrustedEntityRepositoryPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full stack through the real HTTP layer: no mocks, no slices. Proves the two
 * properties the whole design rests on — a consumer can verify a published
 * snapshot offline with nothing but the JWKS, and trust never leaks across
 * tenants.
 *
 * <p>{@link es.in2.trustregistry.snapshot.infrastructure.adapter.persistence.FileSystemPublishedSnapshotRepository}
 * persists across the whole {@code @SpringBootTest} context for this class, so any test that
 * asserts on a published <em>version</em> (as opposed to content only) uses a tenant id no other
 * test method publishes to — otherwise the assertion would be order-dependent on whichever test
 * happened to run first for that tenant. Content-only assertions (no version comparison) are
 * safe to share {@code TENANT_A}/{@code TENANT_B} across tests, as the original tests already do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TrustRegistryEndToEndTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String ORG_ID = "VATES-B12345678";

    @Autowired
    private TestRestTemplate rest;

    // Seeded through the port, not through HTTP: the service exposes no write
    // endpoint — the list arrives as provisioned configuration (AD-9).
    @Autowired
    private TrustedEntityRepositoryPort repository;

    @Autowired
    private TrustAnchorRepositoryPort anchorRepository;

    private static HttpEntity<Void> headers(String tenantId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant", tenantId);
        return new HttpEntity<>(headers);
    }

    private static HttpEntity<Void> headers(String tenantId, String ifNoneMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant", tenantId);
        headers.set("If-None-Match", ifNoneMatch);
        return new HttpEntity<>(headers);
    }

    private void provision(String tenantId, String organizationIdentifier, EntityRole role) {
        repository.save(new TrustedEntity(tenantId, organizationIdentifier, "Acme SL",
                Set.of(role), "pem", Instant.now().minusSeconds(60), null));
    }

    private ResponseEntity<String> fetchSnapshot(String tenantId) {
        return rest.exchange("/trust/v1/snapshot", HttpMethod.GET, headers(tenantId), String.class);
    }

    @Test
    void publishedSnapshot_VerifiedWithThePublishedJwks_IsAuthentic() throws Exception {
        // Arrange
        provision(TENANT_A, ORG_ID, EntityRole.RELYING_PARTY);

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
        provision(TENANT_A, "VATES-ONLY-IN-A", EntityRole.RELYING_PARTY);

        // Act
        String snapshot = rest.exchange("/trust/v1/snapshot", HttpMethod.GET, headers(TENANT_B), String.class).getBody();

        // Assert
        assertThat(JWSObject.parse(snapshot).getPayload().toString()).doesNotContain("VATES-ONLY-IN-A");
    }

    @Test
    void trustCheck_OrganizationRegisteredInAnotherTenant_IsNotTrusted() {
        // Arrange
        provision(TENANT_A, ORG_ID, EntityRole.RELYING_PARTY);

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
        provision(TENANT_A, "VATES-WALLET-ONLY", EntityRole.WALLET_PROVIDER);

        // Act
        Boolean trusted = rest.exchange(
                "/trust/v1/entities/{id}/trusted?role=RELYING_PARTY", HttpMethod.GET,
                headers(TENANT_A), Boolean.class, "VATES-WALLET-ONLY").getBody();

        // Assert: fail closed — registered, but not for this role.
        assertThat(trusted).isFalse();
    }

    @Test
    void snapshot_AnchorsAndEntitiesBothProvisioned_CombinesBothSourcesForTheTenant() throws Exception {
        // Arrange — AC-01: the two sources of one tenant's snapshot, seeded independently
        // (global anchors vs. this tenant's private list), both real ports, no mocks.
        String tenant = "tenant-ac01";
        TrustAnchor anchor = new TrustAnchor("CN=EndToEnd CA", "anchor-pem", "ES", "serviceType",
                TrustServiceStatus.GRANTED, Instant.now().minusSeconds(86400), null);
        anchorRepository.replaceAll(new TrustAnchorSet(List.of(anchor), Instant.now()));
        provision(tenant, "VATES-AC01", EntityRole.RELYING_PARTY);

        // Act
        String snapshot = fetchSnapshot(tenant).getBody();

        // Assert
        String payload = JWSObject.parse(snapshot).getPayload().toString();
        assertThat(payload).contains("EndToEnd CA");
        assertThat(payload).contains("VATES-AC01");
        assertThat(JWSObject.parse(snapshot).getPayload().toJSONObject()).containsEntry("tenantId", tenant);
    }

    @Test
    void snapshot_Published_DeclaresVersionGenerationAndProfile() throws Exception {
        // Arrange — AC-03: version, generation instant and trust profile are all present in
        // the payload a real consumer receives, not just in the unit-tested domain model.
        String tenant = "tenant-ac03";
        provision(tenant, "VATES-AC03", EntityRole.RELYING_PARTY);

        // Act
        String snapshot = fetchSnapshot(tenant).getBody();

        // Assert
        var payload = JWSObject.parse(snapshot).getPayload().toJSONObject();
        assertThat(payload).containsKeys("version", "generatedAt", "timeToLiveSeconds", "trustProfile");
        assertThat(payload.get("version")).isEqualTo(1L);
    }

    @Test
    void signedSnapshot_IfNoneMatchOverTheRealApi_BehavesAsAConditionalRequest() {
        // Arrange — AC-06: the lightweight version check is HTTP conditional requests over the
        // real API (AD-4), not just the mocked-service slice already proven by
        // TrustSnapshotControllerTest (task 20).
        String tenant = "tenant-ac06";
        provision(tenant, "VATES-AC06", EntityRole.RELYING_PARTY);
        ResponseEntity<String> first = fetchSnapshot(tenant);
        String currentETag = first.getHeaders().getETag();

        // Act
        ResponseEntity<String> notModified = rest.exchange(
                "/trust/v1/snapshot", HttpMethod.GET, headers(tenant, currentETag), String.class);
        ResponseEntity<String> staleVersion = rest.exchange(
                "/trust/v1/snapshot", HttpMethod.GET, headers(tenant, "\"999\""), String.class);

        // Assert
        assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(notModified.getBody()).isNullOrEmpty();
        assertThat(staleVersion.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(staleVersion.getBody()).isEqualTo(first.getBody());
    }

    @Test
    void snapshot_ChangeInOneTenant_LeavesTheOtherTenantsVersionUntouched() throws Exception {
        // Arrange — AC-08, the version-stability half not covered by
        // snapshot_EntityRegisteredInAnotherTenant_IsNotIncluded (content only): a change to
        // one tenant must not advance another tenant's published version.
        String changingTenant = "tenant-ac08-changing";
        String stableTenant = "tenant-ac08-stable";
        provision(stableTenant, "VATES-AC08-STABLE", EntityRole.RELYING_PARTY);
        String stableETagBefore = fetchSnapshot(stableTenant).getHeaders().getETag();

        // Act
        provision(changingTenant, "VATES-AC08-CHANGING", EntityRole.RELYING_PARTY);
        fetchSnapshot(changingTenant);
        String stableETagAfter = fetchSnapshot(stableTenant).getHeaders().getETag();

        // Assert
        assertThat(stableETagAfter).isEqualTo(stableETagBefore);
    }

    @Test
    void snapshot_TwoConcurrentRequestsSameTenantNoSourceChange_ReturnSameVersionAndDocument()
            throws Exception {
        // Arrange — EC-03: concurrent requests for a tenant whose sources do not change must
        // not mint a new version for the mere fact of being requested twice, over the real
        // HTTP layer and the real file-backed repository (unit-level CAS behaviour is already
        // proven by SnapshotVersionResolverTest, task 17 — this is the same guarantee under a
        // real concurrent HTTP load).
        String tenant = "tenant-ec03";
        provision(tenant, "VATES-EC03", EntityRole.RELYING_PARTY);
        fetchSnapshot(tenant); // seed a first publication before the concurrent burst

        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<ResponseEntity<String>>> requests = IntStream.range(0, 8)
                    .<Callable<ResponseEntity<String>>>mapToObj(i -> () -> fetchSnapshot(tenant))
                    .toList();

            // Act
            List<Future<ResponseEntity<String>>> results = executor.invokeAll(requests);

            // Assert
            List<ResponseEntity<String>> responses = new ArrayList<>();
            for (Future<ResponseEntity<String>> result : results) {
                responses.add(result.get());
            }
            String firstETag = responses.getFirst().getHeaders().getETag();
            String firstBody = responses.getFirst().getBody();
            assertThat(responses).allSatisfy(response -> {
                assertThat(response.getHeaders().getETag()).isEqualTo(firstETag);
                assertThat(response.getBody()).isEqualTo(firstBody);
            });
        } finally {
            executor.shutdown();
        }
    }
}
