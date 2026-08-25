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
        List<TrustAnchor> usable = officialTrustList.fetchAnchors().stream()
                .filter(TrustAnchor::isUsable)
                .toList();
        repository.replaceAll(usable);
        log.info("Trust anchor sync completed: {} usable anchor(s)", usable.size());
        return usable.size();
    }

    public List<TrustAnchor> currentAnchors() {
        return repository.findAll();
    }
}
