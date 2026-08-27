package es.in2.trustregistry.snapshot.domain.model;

/**
 * Trust profile a snapshot was generated under.
 *
 * <p>This Story only transports the value inside the published snapshot. Deciding what the
 * profile actually is for a given deployment, and enforcing which official anchors are
 * admissible under it, is the responsibility of a later Story ({@code EUD-231}) — carrying the
 * field here must not be read as that enforcement already existing.
 */
public enum TrustProfile {
    DEVELOPMENT,
    STAGING,
    PRODUCTION
}
