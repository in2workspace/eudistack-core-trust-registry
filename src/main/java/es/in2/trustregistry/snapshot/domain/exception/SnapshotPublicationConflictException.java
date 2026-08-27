package es.in2.trustregistry.snapshot.domain.exception;

/**
 * Raised when a publication attempt cannot be resolved after the single retry RC-1 allows.
 *
 * <p>{@code technical-design.md} §3.4.1 RC-1 specifies the compare-and-swap retry policy for
 * concurrent publication attempts of the same tenant in full for its first conflict: the losing
 * writer re-reads the winning publication, compares fingerprints, returns it as-is if unchanged,
 * and otherwise retries exactly once. It does not say what happens if that single retry also
 * conflicts. This is a genuine gap in RC-1, not an oversight in {@link
 * es.in2.trustregistry.snapshot.application.SnapshotVersionResolver}: two consecutive conflicts
 * mean at least three writers raced for the same tenant inside one request's lifetime, which
 * this Story's design (compare-and-swap, no lock, no bounded retry loop — RC-1 is explicit that
 * "no reintenta con una versión nueva" beyond the one retry) treats as a condition to surface
 * rather than resolve blindly.
 *
 * <p>The chosen behaviour is to fail this publication attempt loudly, exactly like {@code ES-03}
 * ("fallo al construir o firmar la instantánea") already requires: the version on record MUST
 * NOT advance as a side effect of this failure, and the previously published instantánea remains
 * intact and servable. Publication is not a one-shot operation — {@code TrustSnapshotService}
 * (task 9) is invoked again on the next request — so the natural recovery is a fresh attempt
 * with fresh reads, not a further retry loop inside this exception's throw site.
 */
public class SnapshotPublicationConflictException extends RuntimeException {

    public SnapshotPublicationConflictException(String tenantId) {
        super("Could not publish snapshot for tenant '" + tenantId
                + "': two consecutive compare-and-swap conflicts (RC-1 allows a single retry)");
    }
}
