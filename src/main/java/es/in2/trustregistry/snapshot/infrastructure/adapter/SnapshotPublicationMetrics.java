package es.in2.trustregistry.snapshot.infrastructure.adapter;

import es.in2.trustregistry.snapshot.domain.port.SnapshotPublicationObserverPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dashboard-visible metrics of what is currently published per tenant ({@code NFR-O-228-01}):
 * the version on record, and whether the official trust it was built from is stale. Same
 * pattern {@link es.in2.trustregistry.anchors.infrastructure.adapter.dss.TrustAnchorSyncScheduler}
 * already established for {@code EUD-227} (constants for metric/tag names,
 * {@code Gauge.builder(...).description(...).register(meterRegistry)}) — the single call site
 * that changes what is published owns recording it, exactly like the scheduler owns recording
 * every sync outcome.
 *
 * <p>These do <b>not</b> replace or duplicate {@code TrustAnchorSyncScheduler}'s own metrics:
 * those report the outcome of a synchronisation attempt (did it succeed, how stale is the
 * anchor set), a concept that exists independently of any tenant. This class reports which
 * version is published <em>for a given tenant</em> and whether <em>that tenant's published
 * snapshot</em> declares official trust as stale — a concept that only exists once a tenant has
 * published at least once, and that {@code EUD-227}'s metrics have no tenant dimension to
 * express.
 *
 * <p>Gauges are registered lazily, the first time a tenant publishes, because the set of tenants
 * is not known upfront (unlike the single, global anchor set {@code TrustAnchorSyncScheduler}
 * reports on). Each tenant gets exactly one gauge per metric, backed by an {@link AtomicLong}
 * this class updates on every publication; Micrometer reads the current value live at scrape
 * time, the same "gauge over live state" idiom the scheduler already uses for
 * {@code trust_registry.anchor_set.age_seconds}.
 *
 * <p>Implements {@link SnapshotPublicationObserverPort} so {@code TrustSnapshotService}
 * (application layer) depends on a domain port, never on this Micrometer-specific adapter
 * directly — the same ports-and-adapters discipline every other driven port in this module
 * already follows.
 */
@Component
public class SnapshotPublicationMetrics implements SnapshotPublicationObserverPort {

    private static final String METRIC_PUBLISHED_VERSION = "trust_registry.snapshot.published_version";
    private static final String METRIC_OFFICIAL_TRUST_STALE = "trust_registry.snapshot.official_trust_stale";
    private static final String TAG_TENANT = "tenant";

    private final MeterRegistry meterRegistry;
    private final Map<String, AtomicLong> publishedVersionByTenant = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> officialTrustStaleByTenant = new ConcurrentHashMap<>();

    public SnapshotPublicationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Safe to call on every {@code publishFor(...)}, including the "fingerprint unchanged"
     * path — the gauge simply reports the same value again, which is the correct live state.
     */
    @Override
    public void recordPublication(String tenantId, long version, boolean officialTrustStale) {
        gaugeFor(publishedVersionByTenant, METRIC_PUBLISHED_VERSION,
                "Version currently published for this tenant", tenantId)
                .set(version);
        gaugeFor(officialTrustStaleByTenant, METRIC_OFFICIAL_TRUST_STALE,
                "1 if the published snapshot declares official trust as stale, 0 otherwise", tenantId)
                .set(officialTrustStale ? 1L : 0L);
    }

    /**
     * Returns the {@link AtomicLong} backing {@code tenantId}'s gauge for {@code metricName},
     * registering the gauge the first time this tenant is seen. Two separate maps (one per
     * metric) rather than a single per-tenant holder, so each metric's gauge registers
     * independently of the other's — a future metric added the same way does not need to touch
     * an existing tenant's holder shape.
     */
    private AtomicLong gaugeFor(Map<String, AtomicLong> holders, String metricName, String description,
                                 String tenantId) {
        return holders.computeIfAbsent(tenantId, tenant -> {
            AtomicLong holder = new AtomicLong();
            Gauge.builder(metricName, holder, SnapshotPublicationMetrics::currentValue)
                    .description(description)
                    .tag(TAG_TENANT, tenant)
                    .register(meterRegistry);
            return holder;
        });
    }

    private static double currentValue(AtomicLong holder) {
        return holder.get();
    }
}
