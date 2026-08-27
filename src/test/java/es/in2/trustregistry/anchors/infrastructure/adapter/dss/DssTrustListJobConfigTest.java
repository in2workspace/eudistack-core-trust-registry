package es.in2.trustregistry.anchors.infrastructure.adapter.dss;

import es.in2.trustregistry.shared.infrastructure.config.TrustRegistryProperties;
import eu.europa.esig.dss.spi.x509.KeyStoreCertificateSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DssTrustListJobConfigTest {

    private static final ResourceLoader RESOURCE_LOADER = new DefaultResourceLoader();

    @Test
    void officialSigningCertificateSource_ValidKeystore_LoadsSuccessfully() {
        // Arrange: a throwaway self-signed keystore, unrelated to the real OJ keystore or
        // task 13's TSL-signing fixtures — this only exercises "a well-formed PKCS12 loads".
        DssTrustListJobConfig config = new DssTrustListJobConfig(properties(
                "classpath:dss-config/valid-test-keystore.p12"), RESOURCE_LOADER);

        // Act
        KeyStoreCertificateSource certificateSource = config.officialSigningCertificateSource();

        // Assert
        assertThat(certificateSource.getCertificates()).isNotEmpty();
    }

    @Test
    void officialSigningCertificateSource_MissingKeystore_FailsFastAtConstruction() {
        // Arrange: ES-01 — a keystore that cannot be found must fail loudly, at context
        // build time, not be swallowed into an empty/absent trust anchor set.
        DssTrustListJobConfig config = new DssTrustListJobConfig(properties(
                "classpath:dss-config/does-not-exist.p12"), RESOURCE_LOADER);

        // Act & Assert
        assertThatThrownBy(config::officialSigningCertificateSource)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist.p12");
    }

    @Test
    void officialSigningCertificateSource_UnreadableKeystore_FailsFastAtConstruction() {
        // Arrange: ES-01 — present but unparseable content must fail the same way as a
        // missing file, not be treated as "empty store, keep going".
        DssTrustListJobConfig config = new DssTrustListJobConfig(properties(
                "classpath:dss-config/invalid-keystore.p12"), RESOURCE_LOADER);

        // Act & Assert
        assertThatThrownBy(config::officialSigningCertificateSource)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invalid-keystore.p12");
    }

    private static TrustRegistryProperties properties(String keystorePath) {
        return new TrustRegistryProperties(
                "https://ec.europa.eu/tools/lotl/eu-lotl.xml",
                keystorePath,
                "build/test-cache",
                86_400L,
                Duration.ofHours(24),
                new TrustRegistryProperties.Sync(Duration.ofSeconds(10), Duration.ofHours(6)));
    }
}
