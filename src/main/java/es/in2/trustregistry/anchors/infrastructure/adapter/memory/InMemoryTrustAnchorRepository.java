package es.in2.trustregistry.anchors.infrastructure.adapter.memory;

import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;
import es.in2.trustregistry.anchors.domain.port.TrustAnchorRepositoryPort;
import org.springframework.stereotype.Repository;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Scaffolding adapter: keeps the anchor set in memory so the service boots with no
 * database. Replaced by a PostgreSQL adapter in US-02 (snapshot persistence).
 *
 * <p>Satisfies the atomic replacement required by AC-07 through the combination of two
 * properties, not through explicit locking: {@link TrustAnchorSet} is an immutable record
 * (its compact constructor copies the anchor list), and {@link AtomicReference#set} /
 * {@link AtomicReference#get} is a single volatile write/read. A concurrent {@link #current()}
 * call therefore always observes one complete, self-consistent {@link TrustAnchorSet} — either
 * the one in force before a given {@link #replaceAll} or the one installed by it — and can never
 * observe a partially-updated or hybrid set.
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
