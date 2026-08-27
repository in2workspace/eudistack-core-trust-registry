package es.in2.trustregistry.anchors.domain.port;

import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;

/** Driven port: local persistence of the anchor set resolved from official sources. */
public interface TrustAnchorRepositoryPort {

    /**
     * Replaces the anchor set currently in force with {@code anchorSet} as a single atomic
     * operation (AC-07): a concurrent {@link #current()} read observes either the previous
     * set in full or the new one in full, never a mix of both.
     */
    void replaceAll(TrustAnchorSet anchorSet);

    /**
     * The anchor set currently in force, together with the instant of its last successful
     * synchronisation (AC-06). Never {@code null} — before any successful synchronisation
     * this is {@link TrustAnchorSet#neverSynced()}.
     */
    TrustAnchorSet current();
}
