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
import es.in2.trustregistry.snapshot.domain.model.TrustSnapshot;
import es.in2.trustregistry.snapshot.domain.port.PublishedSnapshotRepositoryPort;
import es.in2.trustregistry.snapshot.domain.port.SnapshotSignerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

// TODO(EUD-228 task 18): this test still exercises the pre-task-9 shape (one TrustAnchorSet with
// a fixed lastSuccessfulSyncAt, publishFor(tenantId) wired through a stub CAS repository that
// always applies) with literal/mocked values only, to keep compileTestJava green ahead of the
// real rewrite. Task 18 owns replacing these scenarios with the full matrix (AC-01, AC-03, AC-04,
// AC-07, EC-01, EC-02, EC-05, ES-03) — staleness, never-synced anchors, failure injection without
// version advance, and CAS conflict behaviour are not covered here yet.
@ExtendWith(MockitoExtension.class)
class TrustSnapshotServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");
    private static final String TENANT = "sandbox";

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
                "/var/cache/trust-registry", 86400, Duration.ofHours(24),
                new TrustRegistryProperties.Sync(Duration.ofSeconds(10), Duration.ofHours(6)));
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

    @Test
    void publishFor_AnchorsAndEntitiesAvailable_CombinesBothSourcesForTheTenant() {
        // Arrange
        when(anchorService.currentAnchorSet()).thenReturn(new TrustAnchorSet(List.of(anchor()), NOW));
        when(entityService.list(TENANT)).thenReturn(List.of(entity()));

        // Act
        PublishedSnapshot published = service.publishFor(TENANT);

        // Assert
        assertThat(published.tenantId()).isEqualTo(TENANT);
        assertThat(published.signedDocument()).isEqualTo("header.payload.signature");
    }

    @Test
    void publishFor_AnySnapshot_StampsVersionOne() {
        // Arrange
        when(anchorService.currentAnchorSet()).thenReturn(new TrustAnchorSet(List.of(), NOW));
        when(entityService.list(TENANT)).thenReturn(List.of());

        // Act
        PublishedSnapshot published = service.publishFor(TENANT);

        // Assert
        assertThat(published.version()).isEqualTo(1L);
    }

    @Test
    void publishFor_NoPriorPublication_SealsVersionOneEachTimeTheRepositoryIsEmpty() {
        // Arrange
        when(anchorService.currentAnchorSet()).thenReturn(new TrustAnchorSet(List.of(), NOW));
        when(entityService.list(TENANT)).thenReturn(List.of());

        // Act
        long first = service.publishFor(TENANT).version();
        long second = service.publishFor(TENANT).version();

        // Assert — the repository stub above always reports no prior publication, so this only
        // proves the resolver seals "previous + 1" from what it reads; the real accumulation
        // across calls needs a stateful repository, exercised by task 18 / the container test.
        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(1L);
    }

    @Test
    void buildSigned_AnySnapshot_ReturnsWhatTheSignerProduced() {
        // Arrange
        when(anchorService.currentAnchorSet()).thenReturn(new TrustAnchorSet(List.of(), NOW));
        when(entityService.list(TENANT)).thenReturn(List.of());

        // Act
        String signed = service.buildSigned(TENANT);

        // Assert
        assertThat(signed).isEqualTo("header.payload.signature");
    }
}
