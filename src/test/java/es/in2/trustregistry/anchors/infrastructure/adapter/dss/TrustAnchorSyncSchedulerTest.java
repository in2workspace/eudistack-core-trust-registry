package es.in2.trustregistry.anchors.infrastructure.adapter.dss;

import es.in2.trustregistry.anchors.application.TrustAnchorSyncService;
import es.in2.trustregistry.anchors.domain.model.ListRejection;
import es.in2.trustregistry.anchors.domain.model.ListRejection.RejectionReason;
import es.in2.trustregistry.anchors.domain.model.SyncOutcome;
import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;
import es.in2.trustregistry.anchors.domain.model.TrustServiceStatus;
import es.in2.trustregistry.anchors.domain.port.TrustAnchorRepositoryPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrustAnchorSyncSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");

    @Mock
    private TrustAnchorSyncService syncService;

    @Mock
    private DssOfficialTrustListAdapter officialTrustListAdapter;

    @Mock
    private TrustAnchorRepositoryPort repository;

    private SimpleMeterRegistry meterRegistry;
    private Clock clock;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private TrustAnchorSyncScheduler scheduler() {
        return new TrustAnchorSyncScheduler(syncService, officialTrustListAdapter, repository, meterRegistry, clock);
    }

    @Test
    void refresh_SyncSucceeds_DelegatesToTheService() {
        // Arrange
        lenient().when(repository.current()).thenReturn(TrustAnchorSet.neverSynced());
        TrustAnchorSyncScheduler scheduler = scheduler();
        when(syncService.synchronise()).thenReturn(new SyncOutcome(List.of(), List.of()));

        // Act
        scheduler.refresh();

        // Assert
        verify(syncService).synchronise();
    }

    @Test
    void refresh_SyncSucceeds_RecordsASuccessCounterTaggedScheduled() {
        // Arrange: NFR-O-227-01 — the sync result must be dashboard-visible, not just logged.
        lenient().when(repository.current()).thenReturn(TrustAnchorSet.neverSynced());
        TrustAnchorSyncScheduler scheduler = scheduler();
        when(syncService.synchronise()).thenReturn(new SyncOutcome(List.of(), List.of()));

        // Act
        scheduler.refresh();

        // Assert
        assertThat(meterRegistry.get("trust_registry.anchor_sync.result")
                .tag("trigger", "scheduled").tag("outcome", "success").counter().count())
                .isEqualTo(1d);
    }

    @Test
    void refresh_SyncThrows_SwallowsTheErrorSoTheCachedAnchorSetSurvives() {
        // Arrange
        lenient().when(repository.current()).thenReturn(TrustAnchorSet.neverSynced());
        TrustAnchorSyncScheduler scheduler = scheduler();
        when(syncService.synchronise()).thenThrow(new IllegalStateException("LOTL unreachable"));

        // Act & Assert
        assertThatCode(scheduler::refresh).doesNotThrowAnyException();
    }

    @Test
    void refresh_SyncThrows_RecordsAFailureCounterTaggedScheduled() {
        // Arrange
        lenient().when(repository.current()).thenReturn(TrustAnchorSet.neverSynced());
        TrustAnchorSyncScheduler scheduler = scheduler();
        when(syncService.synchronise()).thenThrow(new IllegalStateException("LOTL unreachable"));

        // Act
        scheduler.refresh();

        // Assert
        assertThat(meterRegistry.get("trust_registry.anchor_sync.result")
                .tag("trigger", "scheduled").tag("outcome", "failure").counter().count())
                .isEqualTo(1d);
    }

    @Test
    void refresh_OutcomeHasRejectionsAndStaleNextUpdate_RecordsBothCounters() {
        // Arrange: AC-02/EC-02 — both signals must reach the dashboard, not only the log.
        lenient().when(repository.current()).thenReturn(TrustAnchorSet.neverSynced());
        TrustAnchorSyncScheduler scheduler = scheduler();
        SyncOutcome outcome = new SyncOutcome(List.of(),
                List.of(new ListRejection("FR", RejectionReason.SIGNATURE_INVALID, "tampered content")),
                List.of("https://tl.stale/tl.xml"));
        when(syncService.synchronise()).thenReturn(outcome);

        // Act
        scheduler.refresh();

        // Assert
        assertThat(meterRegistry.get("trust_registry.anchor_sync.rejections")
                .tag("trigger", "scheduled").tag("reason", "SIGNATURE_INVALID").counter().count())
                .isEqualTo(1d);
        assertThat(meterRegistry.get("trust_registry.anchor_sync.stale_next_update")
                .tag("trigger", "scheduled").counter().count())
                .isEqualTo(1d);
    }

    @Test
    void refreshFromCacheOnStartup_CacheFetchSucceeds_AppliesTheOutcomeToTheServedAnchorSet() {
        // Arrange: AC-05/AC-07 — startup must genuinely populate the served repository from
        // cache, not just fetch and log it.
        lenient().when(repository.current()).thenReturn(TrustAnchorSet.neverSynced());
        TrustAnchorSyncScheduler scheduler = scheduler();
        TrustAnchor anchor = new TrustAnchor("CN=Test CA", "pem", "ES",
                "http://uri.etsi.org/TrstSvc/Svctype/CA/QC", TrustServiceStatus.GRANTED,
                Instant.parse("2026-01-01T00:00:00Z"), null);
        SyncOutcome outcome = new SyncOutcome(List.of(anchor), List.of());
        when(officialTrustListAdapter.fetchAnchorsFromCache()).thenReturn(outcome);

        // Act
        scheduler.refreshFromCacheOnStartup();

        // Assert
        verify(officialTrustListAdapter).fetchAnchorsFromCache();
        verify(syncService).applyOutcome(outcome);
        assertThat(meterRegistry.get("trust_registry.anchor_sync.result")
                .tag("trigger", "startup_cache").tag("outcome", "success").counter().count())
                .isEqualTo(1d);
    }

    @Test
    void refreshFromCacheOnStartup_NoCacheAndNoNetwork_AppliesTheEmptyOutcomeWithoutThrowing() {
        // Arrange: EC-04 — an empty, all-rejected outcome is a normal result, not an error, and
        // still gets applied so the served set is marked synced-but-empty, not never-synced.
        lenient().when(repository.current()).thenReturn(TrustAnchorSet.neverSynced());
        TrustAnchorSyncScheduler scheduler = scheduler();
        SyncOutcome outcome = new SyncOutcome(List.of(),
                List.of(new ListRejection("https://ec.europa.eu/lotl", RejectionReason.UNREACHABLE,
                        "no cached entry available")));
        when(officialTrustListAdapter.fetchAnchorsFromCache()).thenReturn(outcome);

        // Act & Assert
        assertThatCode(scheduler::refreshFromCacheOnStartup).doesNotThrowAnyException();
        verify(officialTrustListAdapter).fetchAnchorsFromCache();
        verify(syncService).applyOutcome(outcome);
    }

    @Test
    void refreshFromCacheOnStartup_AdapterThrows_SwallowsTheErrorSoStartupContinues() {
        // Arrange
        lenient().when(repository.current()).thenReturn(TrustAnchorSet.neverSynced());
        TrustAnchorSyncScheduler scheduler = scheduler();
        when(officialTrustListAdapter.fetchAnchorsFromCache())
                .thenThrow(new IllegalStateException("cache directory unreadable"));

        // Act & Assert
        assertThatCode(scheduler::refreshFromCacheOnStartup).doesNotThrowAnyException();
        verify(syncService, never()).applyOutcome(any());
        assertThat(meterRegistry.get("trust_registry.anchor_sync.result")
                .tag("trigger", "startup_cache").tag("outcome", "failure").counter().count())
                .isEqualTo(1d);
    }

    @Test
    void ageGauge_NeverSynced_ReportsZeroAgeAndNeverSyncedFlagSet() {
        // Arrange
        when(repository.current()).thenReturn(TrustAnchorSet.neverSynced());

        // Act
        scheduler();

        // Assert
        assertThat(meterRegistry.get("trust_registry.anchor_set.age_seconds").gauge().value()).isEqualTo(0d);
        assertThat(meterRegistry.get("trust_registry.anchor_set.never_synced").gauge().value()).isEqualTo(1d);
    }

    @Test
    void ageGauge_SyncedInThePast_ReportsElapsedSecondsAndNeverSyncedFlagCleared() {
        // Arrange: last sync was 2 hours before the fixed clock's "now" (AC-06 companion signal).
        Instant lastSync = NOW.minus(Duration.ofHours(2));
        when(repository.current()).thenReturn(new TrustAnchorSet(List.of(), lastSync));

        // Act
        scheduler();

        // Assert
        assertThat(meterRegistry.get("trust_registry.anchor_set.age_seconds").gauge().value())
                .isEqualTo(Duration.ofHours(2).getSeconds());
        assertThat(meterRegistry.get("trust_registry.anchor_set.never_synced").gauge().value()).isEqualTo(0d);
    }
}
