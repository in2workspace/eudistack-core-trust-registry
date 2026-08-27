package es.in2.trustregistry.snapshot.application;

import es.in2.trustregistry.snapshot.domain.exception.SnapshotPublicationConflictException;
import es.in2.trustregistry.snapshot.domain.model.PublishedSnapshot;
import es.in2.trustregistry.snapshot.domain.model.SnapshotFingerprint;
import es.in2.trustregistry.snapshot.domain.model.TrustSnapshot;
import es.in2.trustregistry.snapshot.domain.port.PublishedSnapshotRepositoryPort;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * Isolated versioning policy for one tenant's publication (AD-1): an identical fingerprint
 * returns the previous publication unchanged, a different fingerprint seals {@code previous
 * version + 1}, and the compare-and-swap retry described in {@code technical-design.md}
 * §3.4.1 RC-1 is resolved entirely inside this class.
 *
 * <p>Building the candidate {@link TrustSnapshot} and signing it are both deferred to the
 * caller via {@code snapshotFactory} and {@code signer}, and are only invoked on the "the
 * fingerprint changed" path. This is what keeps AD-1's "identical fingerprint never re-signs"
 * guarantee true structurally rather than by caller discipline: if this resolver signed
 * speculatively before knowing whether a new version is even needed, every request would pay
 * the signing key operation, and the {@code ETag} stability {@code TrustSnapshotController}
 * relies on (AD-4) would depend on the caller remembering not to call {@code sign} — exactly
 * the kind of thing that is cheap to get wrong once and expensive to notice.
 *
 * <p>Deliberately not a Spring bean yet: {@link PublishedSnapshotRepositoryPort} has no adapter
 * until task 13, so self-registering via component scan (as {@code @Component}) would break
 * every {@code @SpringBootTest} context before this class has a single caller. Task 9
 * ({@code TrustSnapshotService}) is where this gets wired in — that is also the task the design
 * already accepts will leave the build red until task 13 exists (see {@code tasks.md} Notes),
 * not this one.
 */
@Slf4j
public class SnapshotVersionResolver {

    private final PublishedSnapshotRepositoryPort repository;

    public SnapshotVersionResolver(PublishedSnapshotRepositoryPort repository) {
        this.repository = repository;
    }

    /**
     * Resolves the publication for {@code tenantId} given the fingerprint of the content that
     * would be published now.
     *
     * @param tenantId           tenant this publication belongs to
     * @param candidateFingerprint fingerprint of the content the caller would publish if a new
     *                             version turns out to be necessary
     * @param snapshotFactory    builds the {@link TrustSnapshot} to sign and publish for a given
     *                           sealed version number; invoked at most twice (initial attempt +
     *                           the single RC-1 retry), and never when the fingerprint is
     *                           unchanged
     * @param signer             signs a freshly built {@link TrustSnapshot}; invoked exactly once
     *                           per {@code snapshotFactory} invocation, never on the unchanged
     *                           path
     * @return the {@link PublishedSnapshot} now on record for {@code tenantId} — either the
     *         untouched previous publication, or the newly sealed one
     * @throws SnapshotPublicationConflictException if the compare-and-swap still conflicts after
     *         the single retry RC-1 allows (see the exception's Javadoc for the reasoning: this
     *         is a genuine gap in RC-1's wording, resolved by failing this attempt rather than
     *         retrying further or returning stale data)
     */
    public PublishedSnapshot resolve(String tenantId,
                                      SnapshotFingerprint candidateFingerprint,
                                      LongFunction<TrustSnapshot> snapshotFactory,
                                      Function<TrustSnapshot, String> signer) {
        Optional<PublishedSnapshot> existing = repository.findByTenant(tenantId);

        if (existing.isPresent() && existing.get().fingerprint().equals(candidateFingerprint)) {
            return existing.get();
        }

        long expectedVersion = existing.map(PublishedSnapshot::version).orElse(0L);
        PublishedSnapshot candidate = seal(tenantId, expectedVersion + 1, candidateFingerprint, snapshotFactory, signer);

        PublishedSnapshotRepositoryPort.CasResult firstAttempt =
                repository.replaceIfVersionIs(tenantId, expectedVersion, candidate);

        return switch (firstAttempt) {
            case PublishedSnapshotRepositoryPort.CasResult.Applied applied -> applied.stored();
            case PublishedSnapshotRepositoryPort.CasResult.Conflict conflict ->
                    resolveConflict(tenantId, candidateFingerprint, conflict.current(), snapshotFactory, signer);
        };
    }

    /**
     * RC-1's single retry: the loser re-reads the winner ({@code conflict.current()}, already
     * supplied by the repository with no extra round trip), compares fingerprints, and either
     * returns the winner as-is (someone else already published the same content) or retries
     * exactly once with the winner's version as the new expected version.
     */
    private PublishedSnapshot resolveConflict(String tenantId,
                                               SnapshotFingerprint candidateFingerprint,
                                               PublishedSnapshot winner,
                                               LongFunction<TrustSnapshot> snapshotFactory,
                                               Function<TrustSnapshot, String> signer) {
        if (winner.fingerprint().equals(candidateFingerprint)) {
            return winner;
        }

        PublishedSnapshot retryCandidate =
                seal(tenantId, winner.version() + 1, candidateFingerprint, snapshotFactory, signer);

        PublishedSnapshotRepositoryPort.CasResult retryAttempt =
                repository.replaceIfVersionIs(tenantId, winner.version(), retryCandidate);

        return switch (retryAttempt) {
            case PublishedSnapshotRepositoryPort.CasResult.Applied applied -> applied.stored();
            case PublishedSnapshotRepositoryPort.CasResult.Conflict ignored -> {
                log.error("Publishing snapshot for tenant '{}' conflicted twice in a row; "
                        + "RC-1 allows a single retry, so this attempt is abandoned and the "
                        + "previously published version is left untouched", tenantId);
                throw new SnapshotPublicationConflictException(tenantId);
            }
        };
    }

    private PublishedSnapshot seal(String tenantId,
                                    long version,
                                    SnapshotFingerprint fingerprint,
                                    LongFunction<TrustSnapshot> snapshotFactory,
                                    Function<TrustSnapshot, String> signer) {
        TrustSnapshot snapshot = snapshotFactory.apply(version);
        String signedDocument = signer.apply(snapshot);
        return new PublishedSnapshot(tenantId, version, fingerprint, signedDocument);
    }
}
