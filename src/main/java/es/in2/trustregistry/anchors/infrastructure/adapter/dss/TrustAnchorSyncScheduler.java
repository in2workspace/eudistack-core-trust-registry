package es.in2.trustregistry.anchors.infrastructure.adapter.dss;

import es.in2.trustregistry.anchors.application.TrustAnchorSyncService;
import es.in2.trustregistry.anchors.domain.model.SyncOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives both refresh modes of {@code AD-2}: a one-off cache-only refresh at startup
 * ({@code AC-05}) and a repeating online refresh on a fixed cadence. A failed online refresh
 * is logged and swallowed: the previously cached anchor set stays in force rather than the
 * service degrading into an empty, deny-everything registry.
 *
 * <p>The startup listener calls {@link DssOfficialTrustListAdapter#fetchAnchorsFromCache()}
 * directly instead of going through {@link TrustAnchorSyncService}/{@code OfficialTrustListPort}
 * like the scheduled path does. This is a deliberate, narrow exception to routing through the
 * port: task 9 is scoped to proving the cache-only fetch works and is visible at startup, not
 * to changing how the served anchor set is populated — that is task 10's scope
 * ({@code TrustAnchorRepositoryPort.replaceAll(...)}, staleness marking). Whether the startup
 * path should also flow through {@link TrustAnchorSyncService} once it supports a
 * cache-vs-online mode is a decision left open for task 10; flagged here rather than assumed.
 */
@Slf4j
@Component
public class TrustAnchorSyncScheduler {

    private final TrustAnchorSyncService syncService;
    private final DssOfficialTrustListAdapter officialTrustListAdapter;

    public TrustAnchorSyncScheduler(TrustAnchorSyncService syncService,
                                     DssOfficialTrustListAdapter officialTrustListAdapter) {
        this.syncService = syncService;
        this.officialTrustListAdapter = officialTrustListAdapter;
    }

    /**
     * Populates the anchors from the on-disk cache once, right after startup, without
     * touching the network ({@code AC-05}, {@code EC-04}). Runs once per application
     * lifetime; the recurring refresh is {@link #refresh()}.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshFromCacheOnStartup() {
        try {
            SyncOutcome outcome = officialTrustListAdapter.fetchAnchorsFromCache();
            log.info("Startup cache-only trust anchor refresh completed: {} anchor(s), {} rejection(s)",
                    outcome.anchors().size(), outcome.rejections().size());
        } catch (RuntimeException error) {
            log.error("Startup cache-only trust anchor refresh failed: {}", error.getMessage(), error);
        }
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
