package es.in2.trustregistry.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exercises the packaged image (same black-box approach as {@link TrustRegistryImageIT}) with
 * a real trust anchor sync against fixtures served by a container, not the real EU LOTL
 * ({@code technical-design.md} &sect;2.3 / task 13's fixtures/tsl README).
 *
 * <p>{@link org.testcontainers.containers.wait.strategy.Wait waits} on
 * {@code /actuator/health/readiness} the same way {@link TrustRegistryImageIT} does; every
 * assertion here goes through the public HTTP surface
 * ({@code /trust/v1/snapshot/plain}, {@code /actuator/prometheus}) because a container test has
 * no in-process access to the application's Spring beans — see the task 15 plan discussion for
 * why this rules out a {@code @SpringBootTest}: the LOTL/TL cross-references are hostnames baked
 * into already-signed XML content (task 13's fixtures), which only resolve via Docker's network
 * DNS between containers on a shared {@link Network}, never from a host-side JVM.
 *
 * <p>The application image is built once ({@link #REGISTRY_IMAGE}, mirroring
 * {@link TrustRegistryImageIT}'s single static {@link ImageFromDockerfile}) and started fresh
 * per scenario from that already-built image, so only container start-up cost is paid more than
 * once, never the Docker image build.
 *
 * <p><b>ES-01</b> (a missing/unreadable official signing-certificate keystore fails startup) is
 * deliberately not re-tested here: {@code DssTrustListJobConfigTest} (task 7) already proves it
 * at the correct level — a {@code @Configuration} bean construction failure — which a black-box
 * container test could only observe as "the container never becomes ready", a strictly weaker
 * signal than that unit test's direct exception assertion.
 *
 * <p>Tagged {@code container} so it stays out of the default {@code test} task, exactly like
 * {@link TrustRegistryImageIT} — run it with {@code ./gradlew integrationTest}.
 */
@Tag("container")
@Testcontainers
class TrustListSyncIT {

    private static final int PORT = 8085;
    private static final String FIXTURES_ALIAS = "tsl-fixtures";
    private static final String KEYSTORE_CONTAINER_PATH = "/test-fixtures/official-test-truststore.p12";
    private static final Path FIXTURES_DIR =
            Path.of("src/test/resources/fixtures/tsl").toAbsolutePath();
    private static final Path TRUSTSTORE_PATH =
            FIXTURES_DIR.resolve("keystore/official-test-truststore.p12");

    private static final ObjectMapper JSON = new ObjectMapper();

    @SuppressWarnings("resource")
    private static final ImageFromDockerfile REGISTRY_IMAGE = new ImageFromDockerfile()
            .withDockerfile(Path.of("Dockerfile").toAbsolutePath())
            .withBuildArg("SKIP_TESTS", "true");

    @BeforeAll
    static void buildImageOnce() {
        // Forces the (shared, cached) image build before any scenario starts a container from
        // it, so build time is never attributed to an individual scenario's own timing.
        REGISTRY_IMAGE.get();
    }

    /**
     * One nginx container per test method, each on its own fresh {@link Network}: fixtures never
     * change, but a fresh container/network pair avoids any cross-scenario coupling through
     * shared mutable container state (e.g. request logs, TCP connection reuse).
     */
    @SuppressWarnings("resource")
    private GenericContainer<?> startFixturesServer(Network network) {
        GenericContainer<?> fixturesServer = new GenericContainer<>("nginx:alpine")
                .withNetwork(network)
                .withNetworkAliases(FIXTURES_ALIAS)
                .withFileSystemBind(FIXTURES_DIR.toString(), "/usr/share/nginx/html", BindMode.READ_ONLY)
                .withExposedPorts(80)
                .waitingFor(Wait.forHttp("/lotl-valid.xml").forStatusCode(200));
        fixturesServer.start();
        return fixturesServer;
    }

    @SuppressWarnings("resource")
    private GenericContainer<?> startRegistry(Network network, String lotlUrl, Path cacheDir) {
        GenericContainer<?> registry = new GenericContainer<>(REGISTRY_IMAGE)
                .withNetwork(network)
                .withExposedPorts(PORT)
                .withEnv("SERVER_PORT", String.valueOf(PORT))
                .withEnv("TRUST_REGISTRY_LOTL_URL", lotlUrl)
                .withEnv("TRUST_REGISTRY_KEYSTORE_PATH", "file:" + KEYSTORE_CONTAINER_PATH)
                .withEnv("TRUST_REGISTRY_CACHE_DIR", "/var/cache/trust-registry")
                // Fires the scheduled online refresh almost immediately, so scenarios that need
                // it don't wait out the production default (application.yaml: PT10S/PT6H).
                .withEnv("TRUST_REGISTRY_SYNC_INITIAL_DELAY", "PT1S")
                .withEnv("TRUST_REGISTRY_SYNC_INTERVAL", "PT1H")
                .withFileSystemBind(TRUSTSTORE_PATH.toString(), KEYSTORE_CONTAINER_PATH, BindMode.READ_ONLY)
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
                .header("X-Tenant", "sandbox")
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode plainSnapshot(GenericContainer<?> registry) {
        try {
            HttpResponse<String> response = get(registry, "/trust/v1/snapshot/plain");
            assertThat(response.statusCode()).isEqualTo(200);
            return JSON.readTree(response.body());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed reading /trust/v1/snapshot/plain", e);
        }
    }

    private static List<String> territoriesOf(JsonNode snapshot) {
        return StreamSupport.stream(snapshot.get("anchors").spliterator(), false)
                .map(anchor -> anchor.get("territory").asText())
                .distinct()
                .toList();
    }

    private static String prometheusMetrics(GenericContainer<?> registry) {
        try {
            return get(registry, "/actuator/prometheus").body();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Failed reading /actuator/prometheus", e);
        }
    }

    @Test
    void synchronise_ValidLotlAndTwoValidNationalLists_PopulatesAllGrantedAnchors() {
        // Given a LOTL authentically signed by a certificate in the official signing-cert
        // store, pointing at two national lists, both validly signed (AC-01)
        try (Network network = Network.newNetwork()) {
            GenericContainer<?> fixturesServer = startFixturesServer(network);
            GenericContainer<?> registry = startRegistry(
                    network, "http://" + FIXTURES_ALIAS + "/lotl-valid.xml", newCacheDir());
            try {
                // When the registry runs a synchronisation (the scheduled online refresh, fired
                // almost immediately by TRUST_REGISTRY_SYNC_INITIAL_DELAY=PT1S)
                // Then the served anchor set contains the granted services of both national
                // lists, and the instant of the sync is recorded (age becomes assertable)
                await().atMost(Duration.ofSeconds(30))
                        .untilAsserted(() -> assertThat(territoriesOf(plainSnapshot(registry)))
                                .containsExactlyInAnyOrder("AA", "BB"));

                String metrics = prometheusMetrics(registry);
                assertThat(metrics).contains("trust_registry_anchor_sync_result_total");
                assertThat(metrics).doesNotContain("trust_registry_anchor_set_never_synced 1.0");
            } finally {
                registry.stop();
                fixturesServer.stop();
            }
        }
    }

    @Test
    void synchronise_OneNationalListTamperedAmongOthers_DiscardsOnlyThatListAndKeepsGoingEc01() {
        // Given a LOTL whose own signature is valid but whose pointers mix a valid national
        // list (BB), a national list whose signature no longer verifies (ZZ, AC-02/ES-02) and
        // an unreachable pointer whose hostname is never registered on the network (EC-01)
        try (Network network = Network.newNetwork()) {
            GenericContainer<?> fixturesServer = startFixturesServer(network);
            GenericContainer<?> registry = startRegistry(
                    network, "http://" + FIXTURES_ALIAS + "/lotl-valid-mixed.xml", newCacheDir());
            try {
                // When the registry runs a synchronisation
                // Then the reachable, validly signed lists are processed normally (BB granted,
                // CC's three services all preserved per AC-03 — only usability differs, not
                // presence) while the tampered (ZZ) and unreachable pointers contribute no
                // anchors, and the sync is not failed in bloc for either
                await().atMost(Duration.ofSeconds(30))
                        .untilAsserted(() -> assertThat(territoriesOf(plainSnapshot(registry)))
                                .containsExactlyInAnyOrder("BB", "CC"));

                String metrics = prometheusMetrics(registry);
                assertThat(metrics).contains("reason=\"SIGNATURE_INVALID\"");
                assertThat(metrics).contains("reason=\"UNREACHABLE\"");
            } finally {
                registry.stop();
                fixturesServer.stop();
            }
        }
    }

    /**
     * <b>NFR-P-227-01</b> requires a complete synchronisation of the LOTL and its national lists
     * to finish in under 10 minutes, measured against the real EU trust framework (~27 national
     * lists, real network latency, real DSS caching/parallelism). This test does <b>not</b>
     * attempt to prove that threshold: it cannot. Timing a 4-pointer fixture set served by a
     * loopback {@code nginx} container has no defensible scaling relationship to 27 real national
     * infrastructures — DSS's per-list cost is dominated by fixed overhead (LOTL parsing,
     * keystore loading, signature verification against a local test certificate) that does not
     * scale linearly with list count, and local container latency is not representative of real
     * network round-trips. Any arithmetic projection from this fixture set to the 10-minute
     * production window (e.g. "4/27 of 10 minutes") would be fabricated precision.
     *
     * <p>What this test <i>does</i> assert is a fixture-scale <b>regression guard</b>: a complete
     * synchronisation against {@code lotl-valid-mixed.xml} (BB valid, ZZ tampered, CC three
     * statuses, plus an unreachable pointer — the same fixture set as
     * {@link #synchronise_OneNationalListTamperedAmongOthers_DiscardsOnlyThatListAndKeepsGoingEc01})
     * finishes within a generous bound, well above what the existing sibling scenarios already
     * demonstrate is normal (their {@code atMost(Duration.ofSeconds(30))} polling ceiling).
     * "Complete" here means every one of the four pointers has a final, recorded outcome — not
     * merely that the reachable anchors appear — so a regression that silently drops or hangs on
     * one pointer while still serving the others would not slip through. A regression that made
     * synchronisation newly blocking (e.g. a synchronous retry loop against the unreachable host,
     * a widened or removed timeout, or DSS validation no longer running in parallel per list)
     * would very plausibly push this fixture-scale sync from a few seconds to tens of seconds or
     * more, and this bound would catch it — that is the entire and only claim this test makes
     * about NFR-P-227-01. Per {@code technical-design.md} &sect;3.7.2's own risk mitigation,
     * fixture measurement is a first step, not a substitute for production-scale validation.
     */
    @Test
    void synchronise_MixedLotlAgainstFixtures_CompletesWellWithinAFixtureScaleRegressionBoundNfrP01() {
        // Given a LOTL pointing at the same mixed set used by the EC-01 scenario: one valid
        // national list (BB), one tampered (ZZ), one with three statuses (CC), and one
        // unreachable pointer
        try (Network network = Network.newNetwork()) {
            GenericContainer<?> fixturesServer = startFixturesServer(network);

            // When the registry starts and runs its first synchronisation, timed from the
            // moment the container reports ready (readiness already passed by the time
            // startRegistry returns) until every pointer has a final, recorded outcome
            Instant syncStart = Instant.now();
            GenericContainer<?> registry = startRegistry(
                    network, "http://" + FIXTURES_ALIAS + "/lotl-valid-mixed.xml", newCacheDir());
            try {
                await().atMost(Duration.ofSeconds(60))
                        .untilAsserted(() -> {
                            assertThat(territoriesOf(plainSnapshot(registry)))
                                    .containsExactlyInAnyOrder("BB", "CC");
                            String metrics = prometheusMetrics(registry);
                            assertThat(metrics).contains("reason=\"SIGNATURE_INVALID\"");
                            assertThat(metrics).contains("reason=\"UNREACHABLE\"");
                        });
                Duration syncDuration = Duration.between(syncStart, Instant.now());

                // Then the complete synchronisation (all four pointers dispositioned) finishes
                // within the fixture-scale regression bound described in the Javadoc above — a
                // smoke check on the sync mechanism itself, not a scaled proof of the
                // production 10-minute/~27-list threshold
                assertThat(syncDuration)
                        .as("fixture-scale synchronisation duration (regression guard for "
                                + "NFR-P-227-01, not a proof of the production threshold)")
                        .isLessThan(Duration.ofSeconds(60));
            } finally {
                registry.stop();
                fixturesServer.stop();
            }
        }
    }

    @Test
    void startup_CacheFromAPriorSuccessfulSyncAndSourcesUnreachable_ServesTheCachedAnchorsAc05() {
        // Given a cache directory populated by a prior, genuinely successful synchronisation
        Path sharedCacheDir = newCacheDir();
        try (Network network = Network.newNetwork()) {
            GenericContainer<?> fixturesServer = startFixturesServer(network);
            GenericContainer<?> onlineRun = startRegistry(
                    network, "http://" + FIXTURES_ALIAS + "/lotl-valid.xml", sharedCacheDir);
            try {
                await().atMost(Duration.ofSeconds(30))
                        .untilAsserted(() -> assertThat(territoriesOf(plainSnapshot(onlineRun)))
                                .containsExactlyInAnyOrder("AA", "BB"));
            } finally {
                onlineRun.stop();
            }
            fixturesServer.stop();

            // When the registry restarts, sharing that same cache directory, with no official
            // source reachable at all (no fixtures container on this network)
            GenericContainer<?> offlineRun = startRegistry(
                    network, "http://" + FIXTURES_ALIAS + "/lotl-valid.xml", sharedCacheDir);
            try {
                // Then it boots successfully and serves the anchors from the cache — the
                // startup cache-only refresh (TrustAnchorSyncScheduler.refreshFromCacheOnStartup,
                // task 9/10) never touches the network, so a missing fixtures container does not
                // block readiness
                assertThat(territoriesOf(plainSnapshot(offlineRun))).containsExactlyInAnyOrder("AA", "BB");

                // And the set's age/never-synced state is declared (its origin is the earlier
                // sync, not a fresh empty one)
                String metrics = prometheusMetrics(offlineRun);
                assertThat(metrics).doesNotContain("trust_registry_anchor_set_never_synced 1.0");
            } finally {
                offlineRun.stop();
            }
        }
    }

    @Test
    void startup_NoCacheAndNoNetwork_BootsWithAnEmptyNeverSyncedSetInsteadOfFailingEc04() {
        // Given no prior cache at all, and no official source reachable (no fixtures container
        // on this network)
        Path freshCacheDir = newCacheDir();
        try (Network network = Network.newNetwork()) {
            GenericContainer<?> registry = startRegistry(
                    network, "http://" + FIXTURES_ALIAS + "/lotl-valid.xml", freshCacheDir);
            try {
                // When the registry starts
                // Then it boots correctly (readiness already passed by the time startRegistry
                // returns) and serves an empty anchor set, marked as never synced rather than as
                // a real, dated, empty outcome — no consumer can get a trust answer by this route
                assertThat(territoriesOf(plainSnapshot(registry))).isEmpty();

                String metrics = prometheusMetrics(registry);
                assertThat(metrics).contains("trust_registry_anchor_set_never_synced 1.0");
            } finally {
                registry.stop();
            }
        }
    }

    private Path newCacheDir() {
        try {
            return java.nio.file.Files.createTempDirectory("trust-registry-cache-");
        } catch (IOException e) {
            throw new IllegalStateException("Failed creating a temporary cache directory", e);
        }
    }
}
