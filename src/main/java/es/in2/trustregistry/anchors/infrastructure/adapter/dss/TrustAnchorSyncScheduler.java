package es.in2.trustregistry.anchors.infrastructure.adapter.dss;

import es.in2.trustregistry.anchors.application.TrustAnchorSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Refreshes the official anchors on a fixed cadence; the cache keeps startup offline safe. */
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
        syncService.synchronise()
                .doOnError(error -> log.error("Trust anchor sync failed: {}", error.getMessage(), error))
                .onErrorResume(error -> reactor.core.publisher.Mono.empty())
                .subscribe();
    }
}
