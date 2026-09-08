package es.in2.trustregistry.anchors.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class TrustAnchorTest {

    private static final Instant GRANTED_FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant WITHDRAWN_FROM = Instant.parse("2026-06-01T00:00:00Z");

    private static TrustAnchor anchor(TrustServiceStatus status, Instant from, Instant until) {
        return new TrustAnchor("CN=Test CA", "pem", "ES",
                "http://uri.etsi.org/TrstSvc/Svctype/CA/QC", status, from, until);
    }

    @Test
    void isUsableAt_GrantedAndStillInForce_ReturnsTrue() {
        // Arrange
        TrustAnchor granted = anchor(TrustServiceStatus.GRANTED, GRANTED_FROM, null);

        // Act
        boolean usable = granted.isUsableAt(Instant.parse("2026-08-27T00:00:00Z"));

        // Assert
        assertThat(usable).isTrue();
    }

    @Test
    void isUsableAt_ActBeforeTheStatusStarted_ReturnsFalse() {
        // Arrange
        TrustAnchor granted = anchor(TrustServiceStatus.GRANTED, GRANTED_FROM, null);

        // Act
        boolean usable = granted.isUsableAt(GRANTED_FROM.minusSeconds(1));

        // Assert
        assertThat(usable).isFalse();
    }

    @Test
    void isUsableAt_ActExactlyWhenTheStatusStarted_ReturnsTrue() {
        // Arrange: the starting instant itself is part of the window (inclusive boundary).
        TrustAnchor granted = anchor(TrustServiceStatus.GRANTED, GRANTED_FROM, null);

        // Act
        boolean usable = granted.isUsableAt(GRANTED_FROM);

        // Assert
        assertThat(usable).isTrue();
    }

    @Test
    void isUsableAt_ActImmediatelyBeforeTheGrantEnded_ReturnsTrue() {
        // Arrange: the ending instant itself is excluded from the window, but the instant
        // right before it is still within it.
        TrustAnchor expired = anchor(TrustServiceStatus.GRANTED, GRANTED_FROM, WITHDRAWN_FROM);

        // Act
        boolean usable = expired.isUsableAt(WITHDRAWN_FROM.minusSeconds(1));

        // Assert
        assertThat(usable).isTrue();
    }

    @Test
    void isUsableAt_ActWhileTheGrantWasStillInForce_ReturnsTrue() {
        // Arrange: the grant ended in June, but the act happened in March.
        TrustAnchor expired = anchor(TrustServiceStatus.GRANTED, GRANTED_FROM, WITHDRAWN_FROM);

        // Act
        boolean usable = expired.isUsableAt(Instant.parse("2026-03-15T00:00:00Z"));

        // Assert: this is the whole point of the window — eIDAS judges the service at the
        // date of the act, so a provider withdrawn later was still qualified back then.
        assertThat(usable).isTrue();
    }

    @Test
    void isUsableAt_ActAfterTheGrantEnded_ReturnsFalse() {
        // Arrange
        TrustAnchor expired = anchor(TrustServiceStatus.GRANTED, GRANTED_FROM, WITHDRAWN_FROM);

        // Act
        boolean usable = expired.isUsableAt(WITHDRAWN_FROM);

        // Assert
        assertThat(usable).isFalse();
    }

    @Test
    void isUsableAt_StatusOtherThanGranted_ReturnsFalseWhicheverTheInstant() {
        // Arrange
        TrustServiceStatus[] notGranted = {
                TrustServiceStatus.WITHDRAWN, TrustServiceStatus.SUSPENDED, TrustServiceStatus.UNKNOWN};

        // Act & Assert
        for (TrustServiceStatus status : notGranted) {
            assertThat(anchor(status, GRANTED_FROM, null).isUsableAt(Instant.parse("2026-08-27T00:00:00Z")))
                    .as("status %s must never be usable", status)
                    .isFalse();
        }
    }
}
