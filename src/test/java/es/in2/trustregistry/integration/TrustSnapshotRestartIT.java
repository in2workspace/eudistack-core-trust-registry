package es.in2.trustregistry.integration;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises EC-04/NFR-R-228-01: the registry process restarting between two requests from the
 * same consumer must not lose either the monotonic version or the ability to verify what a
 * consumer already cached. This is the only honest way to prove {@code AD-3} (published
 * snapshots survive a restart because they live on the same disk-cache volume {@code
 * DssTrustListJobConfig} already relies on for offline startup) and {@code AD-2} (the signing
 * key is injected material, never regenerated, so a cached signature keeps verifying against a
 * freshly published JWKS) at the same time — a {@code @SpringBootTest} only exercises one JVM
 * lifetime and can never observe either invariant surviving a real process boundary.
 *
 * <p>Same pattern as {@code TrustListSyncIT}'s {@code
 * startup_CacheFromAPriorSuccessfulSyncAndSourcesUnreachable_ServesTheCachedAnchorsAc05}: rather
 * than stopping and starting the exact same Testcontainers container instance (which
 * Testcontainers does not support as a first-class operation), a fresh container instance is
 * started against a host-bound cache directory ({@code BindMode.READ_WRITE}) that the first
 * instance already wrote to. That is functionally equivalent to a process restart for what this
 * test needs to prove: EC-04 is about state surviving a restart, not about container identity,
 * and {@link es.in2.trustregistry.snapshot.infrastructure.adapter.persistence.FileSystemPublishedSnapshotRepository}
 * only ever reasons about the files on disk, never about which process instance wrote them.
 *
 * <p>Tagged {@code container} so it stays out of the default {@code test} task, exactly like
 * {@link TrustRegistryImageIT} and {@link TrustListSyncIT} — run it with {@code ./gradlew
 * integrationTest}.
 */
@Tag("container")
@Testcontainers
class TrustSnapshotRestartIT {

    private static final int PORT = 8085;
    private static final String TENANT = "sandbox";
    private static final String SIGNING_KEYSTORE_CONTAINER_PATH = "/test-fixtures/dev-signing-keystore.p12";
    private static final Path SIGNING_KEYSTORE_HOST_PATH = Path.of(
            "src/test/resources/fixtures/snapshot/keystore/dev-signing-keystore.p12").toAbsolutePath();

    @SuppressWarnings("resource")
    private static final ImageFromDockerfile REGISTRY_IMAGE = new ImageFromDockerfile()
            .withDockerfile(Path.of("Dockerfile").toAbsolutePath())
            .withBuildArg("SKIP_TESTS", "true");

    @SuppressWarnings("resource")
    private GenericContainer<?> startRegistry(Path cacheDir) {
        GenericContainer<?> registry = new GenericContainer<>(REGISTRY_IMAGE)
                .withExposedPorts(PORT)
                .withEnv("SERVER_PORT", String.valueOf(PORT))
                // EUD-228 (task 15): application.yaml has no default for these three variables on
                // purpose (ES-01, fail-fast). Same disposable dev-only keystore every other
                // container test in this Story uses — a test fixture (src/test/resources), never
                // bundled into the production jar (quality-report.md B1), mounted via a host bind
                // since it is not on the packaged image's classpath. Every container in this test
                // is discarded, never a real deployment.
                .withEnv("TRUST_REGISTRY_SIGNING_KEYSTORE_PATH", "file:" + SIGNING_KEYSTORE_CONTAINER_PATH)
                .withEnv("TRUST_REGISTRY_SIGNING_KEYSTORE_PASSWORD", "dev-signing-password")
                .withEnv("TRUST_REGISTRY_SIGNING_KEY_ALIAS", "trust-registry-dev")
                .withEnv("TRUST_REGISTRY_CACHE_DIR", "/var/cache/trust-registry")
                .withFileSystemBind(SIGNING_KEYSTORE_HOST_PATH.toString(), SIGNING_KEYSTORE_CONTAINER_PATH, BindMode.READ_ONLY)
                // Host-bound, not the container's own VOLUME: the second instance in each test
                // needs to see exactly what the first instance wrote, which an anonymous volume
                // scoped to the (discarded) first container would not survive.
                .withFileSystemBind(cacheDir.toString(), "/var/cache/trust-registry", BindMode.READ_WRITE)
                .waitingFor(Wait.forHttp("/actuator/health/readiness").forPort(PORT).forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(5));
        registry.start();
        return registry;
    }

    private static HttpResponse<String> get(GenericContainer<?> registry, String path) throws IOException,
            InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://%s:%d%s".formatted(registry.getHost(), registry.getMappedPort(PORT), path)))
                .header("X-Tenant", TENANT)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> fetchSnapshot(GenericContainer<?> registry) throws IOException,
            InterruptedException {
        HttpResponse<String> response = get(registry, "/trust/v1/snapshot");
        assertThat(response.statusCode()).isEqualTo(200);
        return response;
    }

    private static ECKey verificationKey(GenericContainer<?> registry)
            throws IOException, InterruptedException, ParseException {
        HttpResponse<String> jwks = get(registry, "/trust/v1/jwks");
        assertThat(jwks.statusCode()).isEqualTo(200);
        return (ECKey) JWKSet.parse(jwks.body()).getKeys().getFirst();
    }

    private Path newCacheDir() {
        try {
            return Files.createTempDirectory("trust-registry-restart-cache-");
        } catch (IOException e) {
            throw new IllegalStateException("Failed creating a temporary cache directory", e);
        }
    }

    @Test
    void snapshot_RegistryRestartsBetweenTwoRequests_VersionDoesNotRegressAndCachedSignatureStillVerifies()
            throws Exception {
        // Given a consumer that already cached the snapshot of its tenant, published by a first
        // instance of the registry
        Path sharedCacheDir = newCacheDir();
        GenericContainer<?> firstRun = startRegistry(sharedCacheDir);
        String cachedSnapshot;
        String cachedETag;
        try {
            HttpResponse<String> published = fetchSnapshot(firstRun);
            cachedSnapshot = published.body();
            cachedETag = published.headers().firstValue("ETag").orElseThrow();

            ECKey firstKey = verificationKey(firstRun);
            assertThat(JWSObject.parse(cachedSnapshot).verify(new ECDSAVerifier(firstKey))).isTrue();
        } finally {
            firstRun.stop();
        }

        // When the registry process restarts (a fresh container instance, sharing the same
        // on-disk cache the first instance wrote to — see class Javadoc) and the same consumer
        // requests its snapshot again
        GenericContainer<?> secondRun = startRegistry(sharedCacheDir);
        try {
            HttpResponse<String> afterRestart = fetchSnapshot(secondRun);
            String versionAfterRestart = afterRestart.headers().firstValue("ETag").orElseThrow();

            // Then the version the consumer receives is not lower than the one it already had —
            // here, with no source change across the restart, it is the very same version (AD-1
            // never re-signs on an unchanged fingerprint, so this is also byte-for-byte the same
            // document, the strongest form of "did not regress")
            assertThat(versionAfterRestart).isEqualTo(cachedETag);
            assertThat(afterRestart.body()).isEqualTo(cachedSnapshot);

            // And the signature the consumer already cached still verifies against the freshly
            // published verification material — the signing key survived the restart because it
            // is injected material (AD-2), never an ephemeral key regenerated per process
            ECKey secondKey = verificationKey(secondRun);
            assertThat(JWSObject.parse(cachedSnapshot).verify(new ECDSAVerifier(secondKey))).isTrue();
        } finally {
            secondRun.stop();
        }
    }
}
