package es.in2.trustregistry.snapshot.infrastructure.adapter;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SnapshotPublicationMetrics} (NFR-O-228-01): the published version and
 * the official-trust-stale flag must be dashboard-visible per tenant, independently of every
 * other tenant, and must reflect the latest recorded publication rather than accumulate.
 */
class SnapshotPublicationMetricsTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";

    private SimpleMeterRegistry meterRegistry;
    private SnapshotPublicationMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new SnapshotPublicationMetrics(meterRegistry);
    }

    @Test
    void recordPublication_FirstPublicationForATenant_RegistersBothGaugesTaggedWithThatTenant() {
        // Act
        metrics.recordPublication(TENANT_A, 1L, false);

        // Assert
        assertThat(meterRegistry.get("trust_registry.snapshot.published_version")
                .tag("tenant", TENANT_A).gauge().value())
                .isEqualTo(1d);
        assertThat(meterRegistry.get("trust_registry.snapshot.official_trust_stale")
                .tag("tenant", TENANT_A).gauge().value())
                .isEqualTo(0d);
    }

    @Test
    void recordPublication_OfficialTrustStale_ReportsOneOnTheStaleGauge() {
        // Act
        metrics.recordPublication(TENANT_A, 1L, true);

        // Assert
        assertThat(meterRegistry.get("trust_registry.snapshot.official_trust_stale")
                .tag("tenant", TENANT_A).gauge().value())
                .isEqualTo(1d);
    }

    @Test
    void recordPublication_CalledAgainForTheSameTenant_UpdatesTheExistingGaugeRatherThanDuplicating() {
        // Arrange
        metrics.recordPublication(TENANT_A, 1L, false);

        // Act: unchanged fingerprint (AD-1) republishes the same version, or a real content
        // change advances it — either way, the gauge must reflect the latest call.
        metrics.recordPublication(TENANT_A, 2L, true);

        // Assert
        assertThat(meterRegistry.get("trust_registry.snapshot.published_version")
                .tag("tenant", TENANT_A).gauge().value())
                .isEqualTo(2d);
        assertThat(meterRegistry.get("trust_registry.snapshot.official_trust_stale")
                .tag("tenant", TENANT_A).gauge().value())
                .isEqualTo(1d);
        assertThat(meterRegistry.getMeters()).hasSize(2);
    }

    @Test
    void recordPublication_TwoDifferentTenants_EachGetsItsOwnIndependentGauges() {
        // Act
        metrics.recordPublication(TENANT_A, 3L, false);
        metrics.recordPublication(TENANT_B, 7L, true);

        // Assert — AD-5/AC-08: one tenant's published state never overwrites another's.
        assertThat(meterRegistry.get("trust_registry.snapshot.published_version")
                .tag("tenant", TENANT_A).gauge().value())
                .isEqualTo(3d);
        assertThat(meterRegistry.get("trust_registry.snapshot.published_version")
                .tag("tenant", TENANT_B).gauge().value())
                .isEqualTo(7d);
        assertThat(meterRegistry.get("trust_registry.snapshot.official_trust_stale")
                .tag("tenant", TENANT_A).gauge().value())
                .isEqualTo(0d);
        assertThat(meterRegistry.get("trust_registry.snapshot.official_trust_stale")
                .tag("tenant", TENANT_B).gauge().value())
                .isEqualTo(1d);
    }
}
