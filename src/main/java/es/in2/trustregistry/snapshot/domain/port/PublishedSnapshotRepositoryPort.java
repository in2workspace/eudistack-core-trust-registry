package es.in2.trustregistry.snapshot.domain.port;

import es.in2.trustregistry.snapshot.domain.model.PublishedSnapshot;

import java.util.Optional;

/**
 * Driven port: persistence of the last snapshot published per tenant, with compare-and-swap
 * substitution (AD-1, AD-3).
 *
 * <p>There is no {@code save}: the only mutation is {@link #replaceIfVersionIs}, a
 * compare-and-swap keyed on the version the caller last observed. This is deliberate — a blind
 * {@code save} is exactly the "ciega" write AD-1 rules out, because two concurrent writers could
 * each seal a candidate for the same next version without ever noticing the other (RC-1).
 */
public interface PublishedSnapshotRepositoryPort {

    /**
     * The last snapshot published for {@code tenantId}, or empty if this tenant has never had a
     * publication.
     *
     * <p>Empty is not an error: it is the state of every tenant before its first publication
     * (AC-07, EC-02 both publish successfully from this state). It is distinct from a tenant
     * whose last publication combined two empty sources — that tenant has a {@link
     * PublishedSnapshot} on record, just one built from nothing.
     */
    Optional<PublishedSnapshot> findByTenant(String tenantId);

    /**
     * Publishes {@code candidate} in place of the publication currently on record for {@code
     * tenantId}, as a single atomic compare-and-swap against {@code expectedVersion} (RC-1,
     * AD-1). This is the only mutation this port exposes.
     *
     * <p>{@code expectedVersion} is the version the caller last observed for this tenant via
     * {@link #findByTenant(String)} — {@code 0} if the caller observed no prior publication. The
     * swap succeeds only if the version currently on record still matches {@code expectedVersion};
     * otherwise another writer has already published since the caller last read, and the attempt
     * is reported as a conflict rather than silently overwriting it.
     *
     * <p>On conflict, the publication that actually won the race is returned as part of the
     * result — the caller does not need a second {@link #findByTenant(String)} call to see what
     * it lost against. This is what lets the losing writer of RC-1 "re-read the winning
     * publication, compare its fingerprint, and return it as-is if unchanged, retrying at most
     * once otherwise" without an extra round trip, and without the race window a separate
     * follow-up read would reopen.
     *
     * @param tenantId        tenant the candidate belongs to
     * @param expectedVersion version the caller last observed for this tenant, or {@code 0} if
     *                        it observed no prior publication
     * @param candidate       the publication to persist if {@code expectedVersion} still holds
     */
    CasResult replaceIfVersionIs(String tenantId, long expectedVersion, PublishedSnapshot candidate);

    /**
     * Outcome of a {@link #replaceIfVersionIs} attempt. Sealed so a caller's {@code switch} on
     * the result is exhaustive: the retry policy in RC-1 has two — and only two — cases to
     * handle, and the compiler enforces that neither is forgotten.
     */
    sealed interface CasResult permits CasResult.Applied, CasResult.Conflict {

        /** {@code candidate} was persisted. {@code stored} is what is now durable for the tenant. */
        record Applied(PublishedSnapshot stored) implements CasResult {
        }

        /**
         * {@code expectedVersion} no longer matched what was on record when the swap was
         * attempted. {@code current} is the publication that won the race, read by the
         * repository as part of the same attempt.
         */
        record Conflict(PublishedSnapshot current) implements CasResult {
        }
    }
}
