package es.in2.trustregistry.anchors.infrastructure.adapter.dss;

import es.in2.trustregistry.anchors.application.TrustAnchorSyncService;
import es.in2.trustregistry.anchors.domain.model.ListRejection;
import es.in2.trustregistry.anchors.domain.model.SyncOutcome;
import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;
import es.in2.trustregistry.anchors.domain.port.TrustAnchorRepositoryPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

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
 *
 * <p>{@code NFR-O-227-01} requires dashboard-visible metrics of the sync result and of the
 * anchor set's age, so that operations detects a failed synchronisation before a consumer does.
 * This class is the single trigger point for both refresh modes, so it owns recording them:
 * <ul>
 *   <li>{@code trust_registry.anchor_sync.result} — counter, {@code outcome}
 *       ({@code success}/{@code failure}) x {@code trigger}
 *       ({@code scheduled}/{@code startup_cache}).</li>
 *   <li>{@code trust_registry.anchor_sync.rejections} — counter, one increment per
 *       {@link ListRejection}, tagged with its {@code reason} and the run's {@code trigger}.</li>
 *   <li>{@code trust_registry.anchor_sync.stale_next_update} — counter, one increment per list
 *       accepted despite a next-update date already in the past ({@code EC-02}), tagged with
 *       {@code trigger}.</li>
 *   <li>{@code trust_registry.anchor_set.age_seconds} — gauge, read live from
 *       {@link TrustAnchorRepositoryPort#current()} at scrape time rather than only at sync
 *       time, so it keeps growing between runs.</li>
 *   <li>{@code trust_registry.anchor_set.never_synced} — gauge, {@code 1} until the first
 *       successful synchronisation completes, {@code 0} afterwards; kept separate from the age
 *       gauge so "no synchronisation has ever succeeded" is never confused with "just
 *       synced" (age {@code 0}).</li>
 * </ul>
 */
@Slf4j
@Component
public class TrustAnchorSyncScheduler {

    private static final String METRIC_SYNC_RESULT = "trust_registry.anchor_sync.result";
    private static final String METRIC_SYNC_REJECTIONS = "trust_registry.anchor_sync.rejections";
    private static final String METRIC_SYNC_STALE_NEXT_UPDATE = "trust_registry.anchor_sync.stale_next_update";
    private static final String METRIC_SET_AGE_SECONDS = "trust_registry.anchor_set.age_seconds";
    private static final String METRIC_SET_NEVER_SYNCED = "trust_registry.anchor_set.never_synced";

    private static final String TAG_TRIGGER = "trigger";
    private static final String TAG_OUTCOME = "outcome";
    private static final String TAG_REASON = "reason";

    private static final String TRIGGER_SCHEDULED = "scheduled";
    private static final String TRIGGER_STARTUP_CACHE = "startup_cache";

    private final TrustAnchorSyncService syncService;
    private final DssOfficialTrustListAdapter officialTrustListAdapter;
    private final TrustAnchorRepositoryPort repository;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public TrustAnchorSyncScheduler(TrustAnchorSyncService syncService,
                                     DssOfficialTrustListAdapter officialTrustListAdapter,
                                     TrustAnchorRepositoryPort repository,
                                     MeterRegistry meterRegistry,
                                     Clock clock) {
        this.syncService = syncService;
        this.officialTrustListAdapter = officialTrustListAdapter;
        this.repository = repository;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        registerAnchorSetGauges();
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
            recordOutcome(TRIGGER_STARTUP_CACHE, outcome);
        } catch (RuntimeException error) {
            recordFailure(TRIGGER_STARTUP_CACHE);
            log.error("Startup cache-only trust anchor refresh failed: {}", error.getMessage(), error);
        }
    }

    @Scheduled(initialDelayString = "${trust-registry.sync.initial-delay:PT10S}",
            fixedDelayString = "${trust-registry.sync.interval:PT6H}")
    public void refresh() {
        try {
            SyncOutcome outcome = syncService.synchronise();
            recordOutcome(TRIGGER_SCHEDULED, outcome);
        } catch (RuntimeException error) {
            recordFailure(TRIGGER_SCHEDULED);
            log.error("Trust anchor sync failed, keeping the cached anchor set: {}", error.getMessage(), error);
        }
    }

    private void registerAnchorSetGauges() {
        Gauge.builder(METRIC_SET_AGE_SECONDS, repository, this::currentAnchorSetAgeSeconds)
                .description("Age in seconds of the anchor set's last successful synchronisation")
                .register(meterRegistry);
        Gauge.builder(METRIC_SET_NEVER_SYNCED, repository, this::currentlyNeverSynced)
                .description("1 until the first successful synchronisation completes, 0 afterwards")
                .register(meterRegistry);
    }

    private double currentAnchorSetAgeSeconds(TrustAnchorRepositoryPort port) {
        TrustAnchorSet current = port.current();
        if (current.isNeverSynced()) {
            return 0d;
        }
        return Duration.between(current.lastSuccessfulSyncAt(), clock.instant()).getSeconds();
    }

    private double currentlyNeverSynced(TrustAnchorRepositoryPort port) {
        return port.current().isNeverSynced() ? 1d : 0d;
    }

    private void recordOutcome(String trigger, SyncOutcome outcome) {
        Counter.builder(METRIC_SYNC_RESULT)
                .tag(TAG_TRIGGER, trigger)
                .tag(TAG_OUTCOME, "success")
                .register(meterRegistry)
                .increment();
        for (ListRejection rejection : outcome.rejections()) {
            Counter.builder(METRIC_SYNC_REJECTIONS)
                    .tag(TAG_TRIGGER, trigger)
                    .tag(TAG_REASON, rejection.reason().name())
                    .register(meterRegistry)
                    .increment();
        }
        if (outcome.hasStaleNextUpdates()) {
            Counter.builder(METRIC_SYNC_STALE_NEXT_UPDATE)
                    .tag(TAG_TRIGGER, trigger)
                    .register(meterRegistry)
                    .increment(outcome.listsWithStaleNextUpdate().size());
        }
    }

    private void recordFailure(String trigger) {
        Counter.builder(METRIC_SYNC_RESULT)
                .tag(TAG_TRIGGER, trigger)
                .tag(TAG_OUTCOME, "failure")
                .register(meterRegistry)
                .increment();
    }
}
