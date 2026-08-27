package es.in2.trustregistry.anchors.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SyncOutcomeTest {

    private static TrustAnchor anAnchor() {
        return new TrustAnchor("CN=Test CA", "pem", "ES",
                "http://uri.etsi.org/TrstSvc/Svctype/CA/QC", TrustServiceStatus.GRANTED,
                Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    private static ListRejection aRejection() {
        return new ListRejection("FR", ListRejection.RejectionReason.SIGNATURE_INVALID, "tampered content");
    }

    @Test
    void hasRejections_NoRejections_ReturnsFalse() {
        // Arrange
        SyncOutcome outcome = new SyncOutcome(List.of(anAnchor()), List.of());

        // Act & Assert
        assertThat(outcome.hasRejections()).isFalse();
    }

    @Test
    void hasRejections_AtLeastOneRejection_ReturnsTrue() {
        // Arrange: one national list discarded (AC-02) while the rest of the run still produced anchors.
        SyncOutcome outcome = new SyncOutcome(List.of(anAnchor()), List.of(aRejection()));

        // Act & Assert
        assertThat(outcome.hasRejections()).isTrue();
    }

    @Test
    void constructor_LotlRejected_AllowsEmptyAnchorsAlongsideTheRejection() {
        // Arrange: ES-02 — the LOTL itself fails to verify, so no national list can be derived.
        SyncOutcome outcome = new SyncOutcome(List.of(),
                List.of(new ListRejection("LOTL", ListRejection.RejectionReason.SIGNATURE_INVALID, "LOTL signature invalid")));

        // Assert
        assertThat(outcome.anchors()).isEmpty();
        assertThat(outcome.hasRejections()).isTrue();
    }

    @Test
    void constructor_MutatingSourceListsAfterConstruction_DoesNotAffectTheOutcome() {
        // Arrange
        List<TrustAnchor> anchors = new ArrayList<>(List.of(anAnchor()));
        List<ListRejection> rejections = new ArrayList<>(List.of(aRejection()));
        SyncOutcome outcome = new SyncOutcome(anchors, rejections);

        // Act
        anchors.add(anAnchor());
        rejections.add(aRejection());

        // Assert
        assertThat(outcome.anchors()).hasSize(1);
        assertThat(outcome.rejections()).hasSize(1);
    }
}
