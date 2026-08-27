package es.in2.trustregistry.anchors.domain.port;

import es.in2.trustregistry.anchors.domain.model.SyncOutcome;

/**
 * Driven port: reads trust anchors from the official European trust infrastructure
 * (EU LOTL plus the national Trusted Lists it points at, ETSI TS 119 612).
 */
public interface OfficialTrustListPort {

    /**
     * Synchronises the configured LOTL/TL sources.
     *
     * <p>Implementations MUST verify the signature of each list before including its anchors,
     * and MUST process each list independently: one list's verification failure does not abort
     * the run for the others (EC-01, AC-02). Every discarded list is reported as a
     * {@link es.in2.trustregistry.anchors.domain.model.ListRejection} in the returned outcome
     * rather than raised as an exception.
     */
    SyncOutcome fetchAnchors();
}
