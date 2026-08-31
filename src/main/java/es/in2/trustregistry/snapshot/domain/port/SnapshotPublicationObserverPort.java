package es.in2.trustregistry.snapshot.domain.port;

/**
 * Driven port: observes every publication outcome, so it can be reported without the domain or
 * application layer knowing how ({@code NFR-O-228-01}). {@code TrustSnapshotService} calls this
 * on the single call site that changes what is published for a tenant — the same "the trigger
 * point owns recording" pattern {@code TrustAnchorSyncScheduler} already established for
 * {@code EUD-227}'s sync metrics, kept behind a port here because, unlike the scheduler, this
 * observation happens from the application layer, which must not import a Micrometer-specific
 * adapter directly.
 */
public interface SnapshotPublicationObserverPort {

    /**
     * Reports the outcome of a publication for {@code tenantId}: the version now on record, and
     * whether the official trust it declares is stale. Called on every {@code publishFor(...)},
     * including the "fingerprint unchanged" path — the observer reports the same value again,
     * which is the correct current state.
     */
    void recordPublication(String tenantId, long version, boolean officialTrustStale);
}
