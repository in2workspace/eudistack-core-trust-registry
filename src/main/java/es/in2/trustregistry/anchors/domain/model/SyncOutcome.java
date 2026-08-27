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
 * @param anchors     usable anchors gathered from the lists that verified
 * @param rejections  one entry per list discarded during this run, with its reason
 */
public record SyncOutcome(List<TrustAnchor> anchors, List<ListRejection> rejections) {

    public SyncOutcome {
        anchors = List.copyOf(anchors);
        rejections = List.copyOf(rejections);
    }

    /** Whether at least one list was discarded during this synchronisation run. */
    public boolean hasRejections() {
        return !rejections.isEmpty();
    }
}
