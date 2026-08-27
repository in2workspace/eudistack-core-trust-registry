package es.in2.trustregistry.snapshot.domain.model;

import java.util.Objects;

/**
 * The last snapshot published for one tenant: what a repository persists, and what the
 * compare-and-swap mechanism (AD-1, RC-1) reasons about.
 *
 * <p>{@code fingerprint} is the comparison representation: two publications with the same
 * fingerprint are the same content and {@code version} must not advance between them (AD-1).
 * It is the value {@code PublishedSnapshotRepositoryPort.replaceIfVersionIs(...)} (task 5) will
 * compare against to decide whether a swap is safe, and what a losing writer re-reads to check
 * whether it can simply return the winner's publication instead of retrying (RC-1).
 *
 * <p>{@code signedDocument} is the fully signed artefact a consumer receives and verifies
 * offline: the compact JWS produced by {@link es.in2.trustregistry.snapshot.domain.port.SnapshotSignerPort#sign(TrustSnapshot)}.
 * It is stored as-is, not re-derived, so that returning a publication whose fingerprint has not
 * changed never re-signs (AD-1, AD-4 — a changing signature would also break the {@code ETag}
 * stability {@code TrustSnapshotController} relies on).
 *
 * <p>{@code technical-design.md} does not spell out the exact Java shape of "documento firmado"
 * or "representación de comparación" inside this aggregate. This record resolves that gap by
 * reusing the two types the rest of the design already produces for exactly this purpose — the
 * compact JWS {@code String} {@link es.in2.trustregistry.snapshot.domain.port.SnapshotSignerPort#sign(TrustSnapshot)}
 * returns for the document, and {@link SnapshotFingerprint} for the comparison key — rather than
 * introducing a new wrapper type around either.
 *
 * @param tenantId       tenant this publication belongs to; trust never crosses tenant boundaries
 * @param version        version sealed for this publication, monotonically increasing per tenant
 * @param fingerprint     comparison representation of the content this publication was sealed from
 * @param signedDocument  the published snapshot, serialised as a signed JWS in compact form
 */
public record PublishedSnapshot(
        String tenantId,
        long version,
        SnapshotFingerprint fingerprint,
        String signedDocument
) {

    public PublishedSnapshot {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Objects.requireNonNull(signedDocument, "signedDocument must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }
}
