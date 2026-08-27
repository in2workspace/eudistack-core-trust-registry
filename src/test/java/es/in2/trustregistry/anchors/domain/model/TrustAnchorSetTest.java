package es.in2.trustregistry.anchors.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrustAnchorSetTest {

    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final Instant LAST_SYNC = Instant.parse("2026-08-20T00:00:00Z");

    private static TrustAnchor anAnchor() {
        return new TrustAnchor("CN=Test CA", "pem", "ES",
                "http://uri.etsi.org/TrstSvc/Svctype/CA/QC", TrustServiceStatus.GRANTED,
                Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test
    void neverSynced_ReturnsEmptySetMarkedAsNeverSynced() {
        // Act
        TrustAnchorSet set = TrustAnchorSet.neverSynced();

        // Assert
        assertThat(set.anchors()).isEmpty();
        assertThat(set.isNeverSynced()).isTrue();
    }

    @Test
    void isNeverSynced_SyncedSuccessfullyIntoAnEmptyResult_ReturnsFalse() {
        // Arrange: a real, dated outcome with zero anchors — distinct from "never synced".
        TrustAnchorSet set = new TrustAnchorSet(List.of(), LAST_SYNC);

        // Act & Assert
        assertThat(set.isNeverSynced()).isFalse();
        assertThat(set.anchors()).isEmpty();
    }

    @Test
    void isStaleAt_NeverSynced_ReturnsTrueRegardlessOfInstantOrMaxAge() {
        // Arrange
        TrustAnchorSet set = TrustAnchorSet.neverSynced();

        // Act & Assert
        assertThat(set.isStaleAt(Instant.now(), MAX_AGE)).isTrue();
        assertThat(set.isStaleAt(Instant.EPOCH, Duration.ofDays(365))).isTrue();
    }

    @Test
    void isStaleAt_LastSyncWithinMaxAge_ReturnsFalse() {
        // Arrange
        TrustAnchorSet set = new TrustAnchorSet(List.of(anAnchor()), LAST_SYNC);

        // Act
        boolean stale = set.isStaleAt(LAST_SYNC.plus(MAX_AGE).minusSeconds(1), MAX_AGE);

        // Assert
        assertThat(stale).isFalse();
    }

    @Test
    void isStaleAt_LastSyncExactlyAtMaxAge_ReturnsFalse() {
        // Arrange: the boundary itself has not yet exceeded the max age.
        TrustAnchorSet set = new TrustAnchorSet(List.of(anAnchor()), LAST_SYNC);

        // Act
        boolean stale = set.isStaleAt(LAST_SYNC.plus(MAX_AGE), MAX_AGE);

        // Assert
        assertThat(stale).isFalse();
    }

    @Test
    void isStaleAt_LastSyncOlderThanMaxAge_ReturnsTrueButKeepsAnchors() {
        // Arrange
        TrustAnchor anchor = anAnchor();
        TrustAnchorSet set = new TrustAnchorSet(List.of(anchor), LAST_SYNC);

        // Act
        boolean stale = set.isStaleAt(LAST_SYNC.plus(MAX_AGE).plusSeconds(1), MAX_AGE);

        // Assert: excessive age marks the set, it never empties it (AD-3).
        assertThat(stale).isTrue();
        assertThat(set.anchors()).containsExactly(anchor);
    }

    @Test
    void constructor_MutatingTheSourceListAfterConstruction_DoesNotAffectTheSet() {
        // Arrange
        List<TrustAnchor> source = new ArrayList<>(List.of(anAnchor()));
        TrustAnchorSet set = new TrustAnchorSet(source, LAST_SYNC);

        // Act
        source.add(anAnchor());

        // Assert
        assertThat(set.anchors()).hasSize(1);
    }
}
