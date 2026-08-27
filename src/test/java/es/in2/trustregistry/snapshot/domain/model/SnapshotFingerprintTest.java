package es.in2.trustregistry.snapshot.domain.model;

import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;
import es.in2.trustregistry.anchors.domain.model.TrustServiceStatus;
import es.in2.trustregistry.entities.domain.model.EntityRole;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Determinism tests for {@link SnapshotFingerprint} (task 16, pulled forward into task 1 per
 * risk R-1 in {@code technical-design.md} §3.7.2: this property must be proven before anything
 * consuming the fingerprint, such as the version resolver, is built on top of it).
 */
class SnapshotFingerprintTest {

    private static final Instant SYNCED_AT = Instant.parse("2026-08-25T10:00:00Z");

    private static TrustAnchor anchor(String subject, TrustServiceStatus status) {
        return new TrustAnchor(subject, "cert-" + subject, "ES", "service-type",
                status, Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    private static TrustedEntity entity(String organizationIdentifier, String legalName, Set<EntityRole> roles) {
        return new TrustedEntity("cgcom", organizationIdentifier, legalName, roles,
                "cert-" + organizationIdentifier, Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test
    void of_SameContentDifferentCollectionOrder_ProducesSameFingerprint() {
        // Arrange
        TrustAnchor anchorA = anchor("CN=A", TrustServiceStatus.GRANTED);
        TrustAnchor anchorB = anchor("CN=B", TrustServiceStatus.GRANTED);
        Set<EntityRole> rolesInOrder = new LinkedHashSet<>(List.of(EntityRole.RELYING_PARTY, EntityRole.WALLET_PROVIDER));
        Set<EntityRole> rolesReversed = new LinkedHashSet<>(List.of(EntityRole.WALLET_PROVIDER, EntityRole.RELYING_PARTY));
        TrustedEntity entityOne = entity("VATES-1", "Entity One", rolesInOrder);
        TrustedEntity entityTwo = entity("VATES-2", "Entity Two", rolesReversed);

        TrustAnchorSet setInOrder = new TrustAnchorSet(List.of(anchorA, anchorB), SYNCED_AT);
        TrustAnchorSet setReversed = new TrustAnchorSet(List.of(anchorB, anchorA), SYNCED_AT);

        // Act
        SnapshotFingerprint first = SnapshotFingerprint.of(setInOrder, List.of(entityOne, entityTwo), TrustProfile.PRODUCTION);
        SnapshotFingerprint second = SnapshotFingerprint.of(setReversed, List.of(entityTwo, entityOne), TrustProfile.PRODUCTION);

        // Assert
        assertThat(first).isEqualTo(second);
    }

    @Test
    void of_SameInputInvokedTwice_ProducesSameFingerprint() {
        // Arrange
        TrustAnchorSet anchorSet = new TrustAnchorSet(List.of(anchor("CN=A", TrustServiceStatus.GRANTED)), SYNCED_AT);
        List<TrustedEntity> entities = List.of(entity("VATES-1", "Entity One", Set.of(EntityRole.RELYING_PARTY)));

        // Act
        SnapshotFingerprint first = SnapshotFingerprint.of(anchorSet, entities, TrustProfile.PRODUCTION);
        SnapshotFingerprint second = SnapshotFingerprint.of(anchorSet, entities, TrustProfile.PRODUCTION);

        // Assert
        assertThat(first).isEqualTo(second);
    }

    @Test
    void of_DifferentAnchorStatus_ProducesDifferentFingerprint() {
        // Arrange
        TrustAnchorSet granted = new TrustAnchorSet(List.of(anchor("CN=A", TrustServiceStatus.GRANTED)), SYNCED_AT);
        TrustAnchorSet withdrawn = new TrustAnchorSet(List.of(anchor("CN=A", TrustServiceStatus.WITHDRAWN)), SYNCED_AT);

        // Act
        SnapshotFingerprint first = SnapshotFingerprint.of(granted, List.of(), TrustProfile.PRODUCTION);
        SnapshotFingerprint second = SnapshotFingerprint.of(withdrawn, List.of(), TrustProfile.PRODUCTION);

        // Assert
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void of_DifferentEntityLegalName_ProducesDifferentFingerprint() {
        // Arrange
        TrustAnchorSet anchorSet = TrustAnchorSet.neverSynced();
        TrustedEntity original = entity("VATES-1", "Original Name", Set.of(EntityRole.RELYING_PARTY));
        TrustedEntity renamed = entity("VATES-1", "Renamed", Set.of(EntityRole.RELYING_PARTY));

        // Act
        SnapshotFingerprint first = SnapshotFingerprint.of(anchorSet, List.of(original), TrustProfile.PRODUCTION);
        SnapshotFingerprint second = SnapshotFingerprint.of(anchorSet, List.of(renamed), TrustProfile.PRODUCTION);

        // Assert
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void of_DifferentTrustProfile_ProducesDifferentFingerprint() {
        // Arrange
        TrustAnchorSet anchorSet = TrustAnchorSet.neverSynced();

        // Act
        SnapshotFingerprint developmentFingerprint = SnapshotFingerprint.of(anchorSet, List.of(), TrustProfile.DEVELOPMENT);
        SnapshotFingerprint productionFingerprint = SnapshotFingerprint.of(anchorSet, List.of(), TrustProfile.PRODUCTION);

        // Assert
        assertThat(developmentFingerprint).isNotEqualTo(productionFingerprint);
    }

    @Test
    void of_BothSourcesEmpty_ProducesStableFingerprint() {
        // Arrange
        TrustAnchorSet anchorSet = TrustAnchorSet.neverSynced();

        // Act
        SnapshotFingerprint first = SnapshotFingerprint.of(anchorSet, List.of(), TrustProfile.PRODUCTION);
        SnapshotFingerprint second = SnapshotFingerprint.of(anchorSet, List.of(), TrustProfile.PRODUCTION);

        // Assert
        assertThat(first).isEqualTo(second);
        assertThat(first.value()).isNotBlank();
    }

    @Test
    void of_NeverSyncedVsSyncedToEmpty_ProducesSameFingerprint() {
        // Arrange: the fingerprint covers content only, not sync metadata (see class Javadoc) —
        // TrustSnapshot itself is responsible for transporting the never-synced-vs-empty distinction.
        TrustAnchorSet neverSynced = TrustAnchorSet.neverSynced();
        TrustAnchorSet syncedEmpty = new TrustAnchorSet(List.of(), SYNCED_AT);

        // Act
        SnapshotFingerprint first = SnapshotFingerprint.of(neverSynced, List.of(), TrustProfile.PRODUCTION);
        SnapshotFingerprint second = SnapshotFingerprint.of(syncedEmpty, List.of(), TrustProfile.PRODUCTION);

        // Assert
        assertThat(first).isEqualTo(second);
    }
}
