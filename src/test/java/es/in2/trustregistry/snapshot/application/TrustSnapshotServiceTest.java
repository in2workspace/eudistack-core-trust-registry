package es.in2.trustregistry.snapshot.application;

import es.in2.trustregistry.anchors.application.TrustAnchorSyncService;
import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;
import es.in2.trustregistry.anchors.domain.model.TrustServiceStatus;
import es.in2.trustregistry.entities.application.TrustedEntityService;
import es.in2.trustregistry.entities.domain.model.EntityRole;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import es.in2.trustregistry.shared.infrastructure.config.TrustRegistryProperties;
import es.in2.trustregistry.snapshot.domain.model.PublishedSnapshot;
import es.in2.trustregistry.snapshot.domain.model.TrustProfile;
import es.in2.trustregistry.snapshot.domain.model.TrustSnapshot;
import es.in2.trustregistry.snapshot.domain.port.PublishedSnapshotRepositoryPort;
import es.in2.trustregistry.snapshot.domain.port.SnapshotSignerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TrustSnapshotService}: AC-01 (source combination), AC-03 (declared
 * fields), AC-04/EC-01 (staleness and never-synced anchors), AC-07 (both sources empty),
 * EC-02 (tenant with no private entities), EC-05 (expired entity still travels unfiltered),
 * ES-03 (a failure while building or signing must not advance the published version).
 *
 * <p>{@link SnapshotVersionResolver} only invokes {@code signer} on the "content changed" path,
 * so every scenario here is a first publication for its tenant (empty repository) unless the
 * test explicitly needs otherwise — this is what makes {@code signer.sign(...)} always get
 * called and lets an {@link ArgumentCaptor} on it recover the {@link TrustSnapshot} that
 * {@code publishFor} built, which {@link PublishedSnapshot} itself does not expose (see its
 * Javadoc, task 4 Notes).
 */
@ExtendWith(MockitoExtension.class)
class TrustSnapshotServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");
    private static final String TENANT = "cgcom";
    private static final Duration MAX_AGE = Duration.ofHours(24);

    @Mock
    private TrustAnchorSyncService anchorService;

    @Mock
    private TrustedEntityService entityService;

    @Mock
    private SnapshotSignerPort signer;

    @Mock
    private PublishedSnapshotRepositoryPort publishedSnapshotRepository;

    private TrustSnapshotService service;

    @BeforeEach
    void setUp() {
        TrustRegistryProperties properties = new TrustRegistryProperties(
                "https://ec.europa.eu/tools/lotl/eu-lotl.xml", "classpath:keystore/oj-keystore.p12",
                "/var/cache/trust-registry", 86400, MAX_AGE,
                new TrustRegistryProperties.Sync(Duration.ofSeconds(10), Duration.ofHours(6)),
                new TrustRegistryProperties.Signing(
                        "classpath:fixtures/snapshot/keystore/valid-signing-keystore.p12",
                        "snapshot-test-password", "snapshot-signing"),
                TrustProfile.PRODUCTION);
        service = new TrustSnapshotService(anchorService, entityService, signer, properties,
                Clock.fixed(NOW, ZoneOffset.UTC), publishedSnapshotRepository);

        lenient().when(publishedSnapshotRepository.findByTenant(TENANT)).thenReturn(Optional.empty());
        lenient().when(publishedSnapshotRepository.replaceIfVersionIs(eq(TENANT), anyLong(), any(PublishedSnapshot.class)))
                .thenAnswer(invocation -> new PublishedSnapshotRepositoryPort.CasResult.Applied(invocation.getArgument(2)));
        lenient().when(signer.sign(any(TrustSnapshot.class))).thenReturn("header.payload.signature");
    }

    private static TrustAnchor anchor() {
        return new TrustAnchor("CN=Test CA", "pem", "ES", "serviceType",
                TrustServiceStatus.GRANTED, NOW.minusSeconds(86400), null);
    }

    private static TrustedEntity entity() {
        return new TrustedEntity(TENANT, "VATES-B1", "Acme SL",
                Set.of(EntityRole.RELYING_PARTY), "pem", NOW.minusSeconds(60), null);
    }

    /** Captures the {@link TrustSnapshot} the service built and handed to the signer. */
    private TrustSnapshot capturedSnapshot() {
        ArgumentCaptor<TrustSnapshot> captor = ArgumentCaptor.forClass(TrustSnapshot.class);
        verify(signer).sign(captor.capture());
        return captor.getValue();
    }

    // --- AC-01: the snapshot combines both sources for the tenant ---------------------------

    @Test
    void publishFor_AnchorsAndEntitiesAvailable_CombinesBothSourcesForTheTenant() {
        // Arrange
        TrustAnchor anchor = anchor();
        TrustedEntity entity = entity();
        when(anchorService.currentAnchorSet()).thenReturn(new TrustAnchorSet(List.of(anchor), NOW));
        when(entityService.list(TENANT)).thenReturn(List.of(entity));

        // Act
        PublishedSnapshot published = service.publishFor(TENANT);
        TrustSnapshot snapshot = capturedSnapshot();

        // Assert
        assertThat(published.tenantId()).isEqualTo(TENANT);
        assertThat(snapshot.tenantId()).isEqualTo(TENANT);
        assertThat(snapshot.anchors()).containsExactly(anchor);
        assertThat(snapshot.entities()).containsExactly(entity);
    }

    // --- AC-03: version, generation, validity and profile are all declared ------------------

    @Test
    void publishFor_AnySnapshot_DeclaresVersionGenerationValidityAndProfile() {
        // Arrange
        when(anchorService.currentAnchorSet()).thenReturn(new TrustAnchorSet(List.of(), NOW));
        when(entityService.list(TENANT)).thenReturn(List.of());

        // Act
        PublishedSnapshot published = service.publishFor(TENANT);
        TrustSnapshot snapshot = capturedSnapshot();

        // Assert
        assertThat(published.version()).isEqualTo(1L);
        assertThat(snapshot.version()).isEqualTo(1L);
        assertThat(snapshot.generatedAt()).isEqualTo(NOW);
        assertThat(snapshot.expiresAt()).isEqualTo(NOW.plusSeconds(86400));
        assertThat(snapshot.trustProfile()).isEqualTo(TrustProfile.PRODUCTION);
    }

    // --- AC-04 / EC-01: official trust staleness and the never-synced-vs-dated distinction --

    @Test
    void publishFor_LastSyncOlderThanMaxAge_DeclaresOfficialTrustStaleWithRawInstant() {
        // Arrange
        Instant lastSync = NOW.minus(MAX_AGE).minusSeconds(1);
        when(anchorService.currentAnchorSet()).thenReturn(new TrustAnchorSet(List.of(anchor()), lastSync));
        when(entityService.list(TENANT)).thenReturn(List.of());

        // Act
        service.publishFor(TENANT);
        TrustSnapshot snapshot = capturedSnapshot();

        // Assert
        assertThat(snapshot.officialTrustStale()).isTrue();
        assertThat(snapshot.officialTrustLastSyncedAt()).isEqualTo(lastSync);
        assertThat(snapshot.anchors()).isNotEmpty();
    }

    @Test
    void publishFor_LastSyncWithinMaxAge_DeclaresOfficialTrustNotStale() {
        // Arrange
        Instant lastSync = NOW.minus(MAX_AGE).plusSeconds(1);
        when(anchorService.currentAnchorSet()).thenReturn(new TrustAnchorSet(List.of(anchor()), lastSync));
        when(entityService.list(TENANT)).thenReturn(List.of());

        // Act
        service.publishFor(TENANT);
        TrustSnapshot snapshot = capturedSnapshot();

        // Assert
        assertThat(snapshot.officialTrustStale()).isFalse();
    }

    @Test
    void publishFor_AnchorsNeverSynced_DeclaresStaleWithNoLastSyncedInstant() {
        // Arrange
        when(anchorService.currentAnchorSet()).thenReturn(TrustAnchorSet.neverSynced());
        when(entityService.list(TENANT)).thenReturn(List.of());

        // Act
        PublishedSnapshot published = service.publishFor(TENANT);
        TrustSnapshot snapshot = capturedSnapshot();

        // Assert
        assertThat(snapshot.officialTrustStale()).isTrue();
        assertThat(snapshot.officialTrustLastSyncedAt()).isNull();
        assertThat(snapshot.anchors()).isEmpty();
        assertThat(published.signedDocument()).isEqualTo("header.payload.signature");
    }

    // --- AC-07: publishes even when both sources are empty ----------------------------------

    @Test
    void publishFor_BothSourcesEmpty_PublishesSignedAndVersioned() {
        // Arrange
        when(anchorService.currentAnchorSet()).thenReturn(TrustAnchorSet.neverSynced());
        when(entityService.list(TENANT)).thenReturn(List.of());

        // Act
        PublishedSnapshot published = service.publishFor(TENANT);
        TrustSnapshot snapshot = capturedSnapshot();

        // Assert
        assertThat(published.version()).isEqualTo(1L);
        assertThat(published.signedDocument()).isNotBlank();
        assertThat(snapshot.anchors()).isEmpty();
        assertThat(snapshot.entities()).isEmpty();
    }

    // --- EC-02: tenant with no private entities still gets the (global) anchors -------------

    @Test
    void publishFor_TenantWithNoPrivateEntities_PublishesWithEmptyEntitiesAndPresentAnchors() {
        // Arrange
        TrustAnchor anchor = anchor();
        when(anchorService.currentAnchorSet()).thenReturn(new TrustAnchorSet(List.of(anchor), NOW));
        when(entityService.list(TENANT)).thenReturn(List.of());

        // Act
        service.publishFor(TENANT);
        TrustSnapshot snapshot = capturedSnapshot();

        // Assert
        assertThat(snapshot.entities()).isEmpty();
        assertThat(snapshot.anchors()).containsExactly(anchor);
    }

    // --- EC-05: an entity outside its validity window still travels, unfiltered -------------

    @Test
    void publishFor_EntityOutsideValidityWindow_TravelsInTheSnapshotUnfiltered() {
        // Arrange
        TrustedEntity expiredEntity = new TrustedEntity(TENANT, "VATES-B2", "Expired SL",
                Set.of(EntityRole.RELYING_PARTY), "pem", NOW.minusSeconds(7200), NOW.minusSeconds(3600));
        when(anchorService.currentAnchorSet()).thenReturn(new TrustAnchorSet(List.of(), NOW));
        when(entityService.list(TENANT)).thenReturn(List.of(expiredEntity));

        // Act
        service.publishFor(TENANT);
        TrustSnapshot snapshot = capturedSnapshot();

        // Assert
        assertThat(snapshot.entities()).containsExactly(expiredEntity);
        assertThat(expiredEntity.isActiveAt(NOW)).isFalse();
    }

    // --- ES-03: a failure while building or signing must not advance the published version --

    @Test
    void publishFor_SignerThrows_PropagatesWithoutAdvancingPublishedVersion() {
        // Arrange
        when(anchorService.currentAnchorSet()).thenReturn(new TrustAnchorSet(List.of(), NOW));
        when(entityService.list(TENANT)).thenReturn(List.of());
        when(signer.sign(any(TrustSnapshot.class))).thenThrow(new IllegalStateException("signing key unavailable"));

        // Act & Assert
        assertThatThrownBy(() -> service.publishFor(TENANT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signing key unavailable");

        verify(publishedSnapshotRepository, never()).replaceIfVersionIs(eq(TENANT), anyLong(), any());
    }
}
