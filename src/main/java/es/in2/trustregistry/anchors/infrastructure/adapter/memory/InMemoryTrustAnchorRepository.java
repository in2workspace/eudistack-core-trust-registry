package es.in2.trustregistry.anchors.infrastructure.adapter.memory;

import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;
import es.in2.trustregistry.anchors.domain.port.TrustAnchorRepositoryPort;
import org.springframework.stereotype.Repository;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Scaffolding adapter: keeps the anchor set in memory so the service boots with no
 * database. Replaced by a PostgreSQL adapter in US-02 (snapshot persistence).
 */
@Repository
public class InMemoryTrustAnchorRepository implements TrustAnchorRepositoryPort {

    private final AtomicReference<TrustAnchorSet> anchorSet =
            new AtomicReference<>(TrustAnchorSet.neverSynced());

    @Override
    public void replaceAll(TrustAnchorSet newAnchorSet) {
        anchorSet.set(newAnchorSet);
    }

    @Override
    public TrustAnchorSet current() {
        return anchorSet.get();
    }
}
