package es.in2.trustregistry.anchors.application;

import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.port.OfficialTrustListPort;
import es.in2.trustregistry.anchors.domain.port.TrustAnchorRepositoryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Keeps the local copy of the official trust anchors in sync with the LOTL/TL sources.
 * The sync is deliberately atomic (replace-all): a partially refreshed anchor set would
 * make trust decisions non-reproducible.
 */
@Slf4j
@Service
public class TrustAnchorSyncService {

    private final OfficialTrustListPort officialTrustList;
    private final TrustAnchorRepositoryPort repository;

    public TrustAnchorSyncService(OfficialTrustListPort officialTrustList,
                                  TrustAnchorRepositoryPort repository) {
        this.officialTrustList = officialTrustList;
        this.repository = repository;
    }

    public int synchronise() {
        // Deliberately unfiltered: an anchor whose status is no longer granted still
        // answers whether the service was qualified at the date of a past act, and
        // dropping it here would destroy that. Usability is resolved on query.
        List<TrustAnchor> anchors = officialTrustList.fetchAnchors();
        repository.replaceAll(anchors);
        log.info("Trust anchor sync completed: {} anchor(s)", anchors.size());
        return anchors.size();
    }

    public List<TrustAnchor> currentAnchors() {
        return repository.findAll();
    }
}
