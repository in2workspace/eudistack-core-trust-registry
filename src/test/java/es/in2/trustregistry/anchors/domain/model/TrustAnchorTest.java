package es.in2.trustregistry.anchors.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TrustAnchorTest {

    private static TrustAnchor anchorWith(TrustServiceStatus status) {
        return new TrustAnchor("CN=Test CA", "pem", "ES", "http://uri.etsi.org/TrstSvc/Svctype/CA/QC",
                status, Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void isUsable_GrantedService_ReturnsTrue() {
        // Arrange
        TrustAnchor anchor = anchorWith(TrustServiceStatus.GRANTED);

        // Act
        boolean usable = anchor.isUsable();

        // Assert
        assertThat(usable).isTrue();
    }

    @Test
    void isUsable_WithdrawnSuspendedOrUnknownService_ReturnsFalse() {
        // Arrange
        TrustServiceStatus[] notGranted = {
                TrustServiceStatus.WITHDRAWN, TrustServiceStatus.SUSPENDED, TrustServiceStatus.UNKNOWN};

        // Act & Assert
        for (TrustServiceStatus status : notGranted) {
            assertThat(anchorWith(status).isUsable())
                    .as("status %s must not be usable", status)
                    .isFalse();
        }
    }
}
