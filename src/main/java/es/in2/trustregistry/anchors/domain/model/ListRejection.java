package es.in2.trustregistry.anchors.domain.model;

import java.util.Objects;

/**
 * A single official trust list discarded during a synchronisation, with the reason it was
 * discarded.
 *
 * <p>DSS processes every list independently: one list's rejection never aborts the job for the
 * others. The LOTL is not special-cased here — if it is the one that fails, it appears as a
 * rejection like any other list, and the fact that its failure also prevents deriving national
 * lists is a consequence resolved where the outcome is produced, not a property of this record.
 *
 * @param listIdentifier the rejected list — a territory code for a national Trusted List, or the
 *                        LOTL's own identifier
 * @param reason          why the list was discarded
 * @param detail          free-text diagnostic detail (e.g. the underlying verification failure),
 *                        for operators; never used to drive behaviour
 */
public record ListRejection(String listIdentifier, RejectionReason reason, String detail) {

    public ListRejection {
        Objects.requireNonNull(listIdentifier, "listIdentifier must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
    }

    /** Why an official trust list was discarded during synchronisation. */
    public enum RejectionReason {
        /** The list's signature does not verify against the official signing certificates (AC-02, ES-02). */
        SIGNATURE_INVALID,
        /** The list's source did not respond (EC-01). */
        UNREACHABLE
    }
}
