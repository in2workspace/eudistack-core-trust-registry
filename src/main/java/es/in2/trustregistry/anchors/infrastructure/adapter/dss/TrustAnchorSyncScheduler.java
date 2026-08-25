package es.in2.trustregistry.anchors.infrastructure.adapter.dss;

import es.in2.trustregistry.anchors.application.TrustAnchorSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refreshes the official anchors on a fixed cadence. A failed refresh is logged and
 * swallowed: the previously cached anchor set stays in force rather than the service
 * degrading into an empty, deny-everything registry.
 */
@Slf4j
@Component
public class TrustAnchorSyncScheduler {

    private final TrustAnchorSyncService syncService;

    public TrustAnchorSyncScheduler(TrustAnchorSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(initialDelayString = "${trust-registry.sync.initial-delay:PT10S}",
            fixedDelayString = "${trust-registry.sync.interval:PT6H}")
    public void refresh() {
        try {
            syncService.synchronise();
        } catch (RuntimeException error) {
            log.error("Trust anchor sync failed, keeping the cached anchor set: {}", error.getMessage(), error);
        }
    }
}
