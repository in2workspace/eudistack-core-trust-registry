package es.in2.trustregistry.anchors.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The trust anchor set currently in force, together with the instant of its last
 * successful synchronisation.
 *
 * <p>Two distinct empty states exist and must never be confused: a set that has
 * {@link #isNeverSynced() never synced} (no successful synchronisation has ever
 * completed) and a set that synchronised successfully but found no anchors. Only the
 * former means "no trust answer is available by this route"; the latter is a real,
 * dated outcome.
 *
 * <p>Excessive age never empties the set (AD-3): {@link #isStaleAt(Instant, Duration)}
 * only flags it, leaving the decision of what to do about stale trust to the consumer.
 *
 * @param anchors              anchors currently in force, whatever their individual status
 * @param lastSuccessfulSyncAt instant of the last synchronisation that completed
 *                             successfully, or {@code null} if none ever has
 */
public record TrustAnchorSet(List<TrustAnchor> anchors, Instant lastSuccessfulSyncAt) {

    public TrustAnchorSet {
        anchors = List.copyOf(anchors);
    }

    /** A set that has never completed a successful synchronisation: empty and undated. */
    public static TrustAnchorSet neverSynced() {
        return new TrustAnchorSet(List.of(), null);
    }

    /**
     * Whether this set has never completed a successful synchronisation, as opposed to
     * having synchronised successfully into an empty result.
     */
    public boolean isNeverSynced() {
        return lastSuccessfulSyncAt == null;
    }

    /**
     * Whether, at {@code moment}, this set's last successful synchronisation is older
     * than {@code maxAge}. A set that never synced is always stale.
     */
    public boolean isStaleAt(Instant moment, Duration maxAge) {
        if (isNeverSynced()) {
            return true;
        }
        return moment.isAfter(lastSuccessfulSyncAt.plus(maxAge));
    }
}
