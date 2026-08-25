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
 */
public record TrustSnapshot(
        String tenantId,
        long version,
        Instant generatedAt,
        long timeToLiveSeconds,
        List<TrustAnchor> anchors,
        List<TrustedEntity> entities
) {

    public Instant expiresAt() {
        return generatedAt.plusSeconds(timeToLiveSeconds);
    }

    public boolean isExpiredAt(Instant moment) {
        return !moment.isBefore(expiresAt());
    }
}
