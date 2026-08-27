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
 * directly instead of {@link TrustAnchorSyncService#synchronise()}/{@code OfficialTrustListPort}
 * like the scheduled path does — {@code OfficialTrustListPort} only exposes the online refresh
 * ({@code AD-2}), so the cache-only fetch has no port method to go through. It then hands the
 * resulting {@link SyncOutcome} to {@link TrustAnchorSyncService#applyOutcome(SyncOutcome)} so
 * the served anchor set is genuinely populated from cache at startup ({@code AC-05},
 * {@code AC-07}) using the exact same "build a set, replace atomically" step the scheduled
 * refresh uses, instead of duplicating it here.
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
     * Populates the served anchor set from the on-disk cache once, right after startup,
     * without touching the network ({@code AC-05}, {@code EC-04}). Runs once per application
     * lifetime; the recurring refresh is {@link #refresh()}.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshFromCacheOnStartup() {
        try {
            SyncOutcome outcome = officialTrustListAdapter.fetchAnchorsFromCache();
            syncService.applyOutcome(outcome);
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
