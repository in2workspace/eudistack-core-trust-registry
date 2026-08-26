package es.in2.trustregistry.integration;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the packaged image, not the Spring context: the Dockerfile, the
 * non-root user, the cache directory and the entrypoint. While there is no
 * deployed environment, this stands in for a smoke test against one.
 *
 * <p>Tagged {@code container} so it stays out of the default {@code test} task
 * — run it with {@code ./gradlew integrationTest}.
 */
@Tag("container")
@Testcontainers
class TrustRegistryImageIT {

    private static final int PORT = 8085;

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REGISTRY = new GenericContainer<>(
            new ImageFromDockerfile()
                    .withDockerfile(Path.of("Dockerfile"))
                    // The build job already ran the suite for the quality gate;
                    // re-running it inside the image would double the work.
                    .withBuildArg("SKIP_TESTS", "true"))
            .withExposedPorts(PORT)
            .withEnv("SERVER_PORT", String.valueOf(PORT))
            .waitingFor(Wait.forHttp("/actuator/health/readiness").forPort(PORT).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(5));

    private static HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://%s:%d%s".formatted(REGISTRY.getHost(), REGISTRY.getMappedPort(PORT), path)))
                .header("X-Tenant", "sandbox")
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void packagedImage_Booted_ServesASnapshotVerifiableWithItsPublishedJwks() throws Exception {
        // Act
        HttpResponse<String> jwks = get("/trust/v1/jwks");
        HttpResponse<String> snapshot = get("/trust/v1/snapshot");

        // Assert
        assertThat(jwks.statusCode()).isEqualTo(200);
        assertThat(snapshot.statusCode()).isEqualTo(200);

        ECKey key = (ECKey) JWKSet.parse(jwks.body()).getKeys().getFirst();
        assertThat(JWSObject.parse(snapshot.body()).verify(new ECDSAVerifier(key))).isTrue();
    }

    @Test
    void packagedImage_Booted_RunsAsNonRootAndOwnsItsCacheDirectory() throws Exception {
        // Act
        String whoami = REGISTRY.execInContainer("whoami").getStdout().strip();
        String cacheDir = REGISTRY.execInContainer("ls", "-ld", "/var/cache/trust-registry").getStdout();

        // Assert: the image must not run privileged, and the sync must be able
        // to write the cache that keeps the service usable without network.
        assertThat(whoami).isEqualTo("nonroot");
        assertThat(cacheDir).contains("nonroot");
    }
}
