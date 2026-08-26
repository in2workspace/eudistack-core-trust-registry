package es.in2.trustregistry.snapshot.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrustSnapshotTest {

    private static final Instant GENERATED_AT = Instant.parse("2026-08-25T10:00:00Z");

    private static TrustSnapshot snapshotWithTtl(long seconds) {
        return new TrustSnapshot("sandbox", 1L, GENERATED_AT, seconds, List.of(), List.of());
    }

    @Test
    void expiresAt_SnapshotWithTtl_ReturnsGenerationInstantPlusTtl() {
        // Arrange
        TrustSnapshot snapshot = snapshotWithTtl(3600);

        // Act
        Instant expiry = snapshot.expiresAt();

        // Assert
        assertThat(expiry).isEqualTo(GENERATED_AT.plusSeconds(3600));
    }

    @Test
    void isExpiredAt_InstantBeforeExpiry_ReturnsFalse() {
        // Arrange
        TrustSnapshot snapshot = snapshotWithTtl(3600);

        // Act
        boolean expired = snapshot.isExpiredAt(GENERATED_AT.plusSeconds(3599));

        // Assert
        assertThat(expired).isFalse();
    }

    @Test
    void isExpiredAt_InstantExactlyAtExpiry_ReturnsTrue() {
        // Arrange
        TrustSnapshot snapshot = snapshotWithTtl(3600);

        // Act
        boolean expired = snapshot.isExpiredAt(GENERATED_AT.plusSeconds(3600));

        // Assert
        assertThat(expired).isTrue();
    }
}
