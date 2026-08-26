package es.in2.trustregistry.anchors.infrastructure.adapter.dss;

import es.in2.trustregistry.anchors.application.TrustAnchorSyncService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrustAnchorSyncSchedulerTest {

    @Mock
    private TrustAnchorSyncService syncService;

    @Test
    void refresh_SyncSucceeds_DelegatesToTheService() {
        // Arrange
        TrustAnchorSyncScheduler scheduler = new TrustAnchorSyncScheduler(syncService);
        when(syncService.synchronise()).thenReturn(3);

        // Act
        scheduler.refresh();

        // Assert
        verify(syncService).synchronise();
    }

    @Test
    void refresh_SyncThrows_SwallowsTheErrorSoTheCachedAnchorSetSurvives() {
        // Arrange
        TrustAnchorSyncScheduler scheduler = new TrustAnchorSyncScheduler(syncService);
        when(syncService.synchronise()).thenThrow(new IllegalStateException("LOTL unreachable"));

        // Act & Assert
        assertThatCode(scheduler::refresh).doesNotThrowAnyException();
    }
}
