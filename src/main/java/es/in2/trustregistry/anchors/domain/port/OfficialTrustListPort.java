package es.in2.trustregistry.anchors.domain.port;

import es.in2.trustregistry.anchors.domain.model.TrustAnchor;

import java.util.List;

/**
 * Driven port: reads trust anchors from the official European trust infrastructure
 * (EU LOTL plus the national Trusted Lists it points at, ETSI TS 119 612).
 */
public interface OfficialTrustListPort {

    /**
     * Synchronises the configured LOTL/TL sources and returns every anchor found.
     * Implementations MUST verify the signature of each list before returning anchors.
     */
    List<TrustAnchor> fetchAnchors();
}
