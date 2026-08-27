package es.in2.trustregistry.snapshot.domain.model;

import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;

import java.time.Instant;
import java.util.List;

/**
 * Immutable, versioned view of everything a consumer needs to take a trust decision
 * locally: the official anchors plus the private entities of one tenant.
 *
 * <p>The snapshot is what makes distributed evaluation possible. Consumers cache it,
 * verify its signature and then validate certificate chains without calling this service
 * on every request, which keeps the Verifier available and the offline validator usable.
 *
 * <p>{@code officialTrustLastSyncedAt} mirrors the same "never synced" vs "synced to an
 * empty/dated result" distinction that {@link TrustAnchorSet} already encodes: {@code null}
 * means no successful synchronisation has ever completed, a non-null instant is a real,
 * dated outcome. {@code officialTrustStale} is a pre-computed flag — this record does not
 * decide staleness itself; that requires {@code TrustRegistryProperties.maxAge()}, which is
 * an application-layer concern (see {@code TrustSnapshotService}).
 *
 * @param officialTrustStale          whether the official trust anchors are considered
 *                                    stale, already resolved against the configured max age
 * @param officialTrustLastSyncedAt   instant of the last successful anchor synchronisation,
 *                                    or {@code null} if the anchor set has never synced
 */
public record TrustSnapshot(
        String tenantId,
        long version,
        Instant generatedAt,
        long timeToLiveSeconds,
        List<TrustAnchor> anchors,
        List<TrustedEntity> entities,
        TrustProfile trustProfile,
        boolean officialTrustStale,
        Instant officialTrustLastSyncedAt
) {

    public Instant expiresAt() {
        return generatedAt.plusSeconds(timeToLiveSeconds);
    }

    public boolean isExpiredAt(Instant moment) {
        return !moment.isBefore(expiresAt());
    }
}
