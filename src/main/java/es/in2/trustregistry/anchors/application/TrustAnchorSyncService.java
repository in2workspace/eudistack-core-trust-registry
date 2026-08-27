package es.in2.trustregistry.anchors.application;

import es.in2.trustregistry.anchors.domain.model.SyncOutcome;
import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;
import es.in2.trustregistry.anchors.domain.port.OfficialTrustListPort;
import es.in2.trustregistry.anchors.domain.port.TrustAnchorRepositoryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Keeps the local copy of the official trust anchors in sync with the LOTL/TL sources.
 *
 * <p>The sync is atomic (AC-07): the {@link SyncOutcome} is fetched and a full
 * {@link TrustAnchorSet} built in a local variable before the single
 * {@link TrustAnchorRepositoryPort#replaceAll(TrustAnchorSet)} call, so a concurrent read
 * never observes a mix of the previous and new set. The same shape gives ES-03 for free: if
 * {@link OfficialTrustListPort#fetchAnchors()} fails, {@code replaceAll} is never reached and
 * the previously stored set is left untouched, with no rollback logic needed.
 */
@Slf4j
@Service
public class TrustAnchorSyncService {

    private final OfficialTrustListPort officialTrustList;
    private final TrustAnchorRepositoryPort repository;
    private final Clock clock;

    public TrustAnchorSyncService(OfficialTrustListPort officialTrustList,
                                  TrustAnchorRepositoryPort repository,
                                  Clock clock) {
        this.officialTrustList = officialTrustList;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Runs one online synchronisation and returns its {@link SyncOutcome} so callers (metrics,
     * operational logging — ES-03, NFR-O-227-01) learn about rejected lists, not just the
     * anchors that were kept.
     *
     * <p>Deliberately unfiltered: an anchor whose status is no longer granted still answers
     * whether the service was qualified at the date of a past act, and dropping it here would
     * destroy that (AD-4/AC-03). Usability is resolved on query, never here.
     */
    public SyncOutcome synchronise() {
        SyncOutcome outcome = officialTrustList.fetchAnchors();
        applyOutcome(outcome);
        return outcome;
    }

    /**
     * Applies an already-fetched {@link SyncOutcome} to the served anchor set, without
     * fetching one itself.
     *
     * <p>Shared by {@link #synchronise()} (online refresh) and the startup cache-only refresh
     * ({@code TrustAnchorSyncScheduler}, {@code AD-2}/{@code AC-05}), which populates its
     * outcome via {@code DssOfficialTrustListAdapter.fetchAnchorsFromCache()} instead of
     * {@link OfficialTrustListPort#fetchAnchors()}. Both refresh modes need the exact same
     * "build a {@link TrustAnchorSet}, replace atomically" step, so it lives here once rather
     * than being duplicated in the scheduler, which has no reason to know about {@link Clock}
     * or {@link TrustAnchorRepositoryPort}.
     */
    public void applyOutcome(SyncOutcome outcome) {
        TrustAnchorSet newAnchorSet = new TrustAnchorSet(outcome.anchors(), Instant.now(clock));
        repository.replaceAll(newAnchorSet);
        log.info("Trust anchor sync completed: {} anchor(s), {} rejection(s)",
                outcome.anchors().size(), outcome.rejections().size());
    }

    public List<TrustAnchor> currentAnchors() {
        return repository.current().anchors();
    }
}
