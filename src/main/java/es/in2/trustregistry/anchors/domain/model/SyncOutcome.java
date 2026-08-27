package es.in2.trustregistry.anchors.domain.model;

import java.util.List;

/**
 * Result of a single official trust list synchronisation run: the anchors gathered from the
 * lists that verified, plus one {@link ListRejection} per list that did not.
 *
 * <p>A rejection never aborts the run for the other lists (EC-01, AC-02): each list is
 * independent, and this record simply carries whatever the run produced. Whether a rejection
 * (for instance, of the LOTL itself, ES-02) also means no national list could be derived is a
 * fact reflected in the shape of the outcome — an empty {@link #anchors()} alongside the
 * rejection — not something this record decides or filters.
 *
 * <p>{@code listsWithStaleNextUpdate} is a distinct, non-rejecting signal (EC-02): a list whose
 * declared next-update date has already passed is still accepted — its anchors are gathered
 * normally — but the condition must remain visible to operations ({@code NFR-O-227-01}). It is
 * not a {@link ListRejection} because the list was not discarded.
 *
 * @param anchors                  usable anchors gathered from the lists that verified
 * @param rejections               one entry per list discarded during this run, with its reason
 * @param listsWithStaleNextUpdate identifiers of lists accepted despite a next-update date
 *                                 already in the past (EC-02)
 */
public record SyncOutcome(List<TrustAnchor> anchors, List<ListRejection> rejections,
                           List<String> listsWithStaleNextUpdate) {

    public SyncOutcome {
        anchors = List.copyOf(anchors);
        rejections = List.copyOf(rejections);
        listsWithStaleNextUpdate = List.copyOf(listsWithStaleNextUpdate);
    }

    /** Convenience constructor for callers with no stale-next-update lists to report. */
    public SyncOutcome(List<TrustAnchor> anchors, List<ListRejection> rejections) {
        this(anchors, rejections, List.of());
    }

    /** Whether at least one list was discarded during this synchronisation run. */
    public boolean hasRejections() {
        return !rejections.isEmpty();
    }

    /** Whether at least one accepted list declared a next-update date already in the past. */
    public boolean hasStaleNextUpdates() {
        return !listsWithStaleNextUpdate.isEmpty();
    }
}
