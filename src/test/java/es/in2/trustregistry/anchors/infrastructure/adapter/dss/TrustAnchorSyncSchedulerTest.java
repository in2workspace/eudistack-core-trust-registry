package es.in2.trustregistry.anchors.infrastructure.adapter.dss;

import es.in2.trustregistry.anchors.application.TrustAnchorSyncService;
import es.in2.trustregistry.anchors.domain.model.ListRejection;
import es.in2.trustregistry.anchors.domain.model.ListRejection.RejectionReason;
import es.in2.trustregistry.anchors.domain.model.SyncOutcome;
import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustServiceStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrustAnchorSyncSchedulerTest {

    @Mock
    private TrustAnchorSyncService syncService;

    @Mock
    private DssOfficialTrustListAdapter officialTrustListAdapter;

    @Test
    void refresh_SyncSucceeds_DelegatesToTheService() {
        // Arrange
        TrustAnchorSyncScheduler scheduler = new TrustAnchorSyncScheduler(syncService, officialTrustListAdapter);
        when(syncService.synchronise()).thenReturn(new SyncOutcome(List.of(), List.of()));

        // Act
        scheduler.refresh();

        // Assert
        verify(syncService).synchronise();
    }

    @Test
    void refresh_SyncThrows_SwallowsTheErrorSoTheCachedAnchorSetSurvives() {
        // Arrange
        TrustAnchorSyncScheduler scheduler = new TrustAnchorSyncScheduler(syncService, officialTrustListAdapter);
        when(syncService.synchronise()).thenThrow(new IllegalStateException("LOTL unreachable"));

        // Act & Assert
        assertThatCode(scheduler::refresh).doesNotThrowAnyException();
    }

    @Test
    void refreshFromCacheOnStartup_CacheFetchSucceeds_AppliesTheOutcomeToTheServedAnchorSet() {
        // Arrange: AC-05/AC-07 — startup must genuinely populate the served repository from
        // cache, not just fetch and log it.
        TrustAnchorSyncScheduler scheduler = new TrustAnchorSyncScheduler(syncService, officialTrustListAdapter);
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
    }

    @Test
    void refreshFromCacheOnStartup_NoCacheAndNoNetwork_AppliesTheEmptyOutcomeWithoutThrowing() {
        // Arrange: EC-04 — an empty, all-rejected outcome is a normal result, not an error, and
        // still gets applied so the served set is marked synced-but-empty, not never-synced.
        TrustAnchorSyncScheduler scheduler = new TrustAnchorSyncScheduler(syncService, officialTrustListAdapter);
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
        TrustAnchorSyncScheduler scheduler = new TrustAnchorSyncScheduler(syncService, officialTrustListAdapter);
        when(officialTrustListAdapter.fetchAnchorsFromCache())
                .thenThrow(new IllegalStateException("cache directory unreadable"));

        // Act & Assert
        assertThatCode(scheduler::refreshFromCacheOnStartup).doesNotThrowAnyException();
        verify(syncService, never()).applyOutcome(any());
    }
}
