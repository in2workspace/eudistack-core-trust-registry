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
        when(syncService.synchronise()).thenReturn(3);

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
    void refreshFromCacheOnStartup_CacheFetchSucceeds_DelegatesToTheAdapter() {
        // Arrange: AC-05 — startup populates from cache via the adapter directly.
        TrustAnchorSyncScheduler scheduler = new TrustAnchorSyncScheduler(syncService, officialTrustListAdapter);
        TrustAnchor anchor = new TrustAnchor("CN=Test CA", "pem", "ES",
                "http://uri.etsi.org/TrstSvc/Svctype/CA/QC", TrustServiceStatus.GRANTED,
                Instant.parse("2026-01-01T00:00:00Z"), null);
        when(officialTrustListAdapter.fetchAnchorsFromCache())
                .thenReturn(new SyncOutcome(List.of(anchor), List.of()));

        // Act
        scheduler.refreshFromCacheOnStartup();

        // Assert
        verify(officialTrustListAdapter).fetchAnchorsFromCache();
    }

    @Test
    void refreshFromCacheOnStartup_NoCacheAndNoNetwork_SwallowsEmptyOutcomeWithoutThrowing() {
        // Arrange: EC-04 — an empty, all-rejected outcome is a normal result, not an error.
        TrustAnchorSyncScheduler scheduler = new TrustAnchorSyncScheduler(syncService, officialTrustListAdapter);
        when(officialTrustListAdapter.fetchAnchorsFromCache()).thenReturn(new SyncOutcome(List.of(),
                List.of(new ListRejection("https://ec.europa.eu/lotl", RejectionReason.UNREACHABLE,
                        "no cached entry available"))));

        // Act & Assert
        assertThatCode(scheduler::refreshFromCacheOnStartup).doesNotThrowAnyException();
        verify(officialTrustListAdapter).fetchAnchorsFromCache();
    }

    @Test
    void refreshFromCacheOnStartup_AdapterThrows_SwallowsTheErrorSoStartupContinues() {
        // Arrange
        TrustAnchorSyncScheduler scheduler = new TrustAnchorSyncScheduler(syncService, officialTrustListAdapter);
        when(officialTrustListAdapter.fetchAnchorsFromCache())
                .thenThrow(new IllegalStateException("cache directory unreadable"));

        // Act & Assert
        assertThatCode(scheduler::refreshFromCacheOnStartup).doesNotThrowAnyException();
    }
}
