package es.in2.trustregistry.anchors.infrastructure.adapter.memory;

import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.port.TrustAnchorRepositoryPort;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Scaffolding adapter: keeps the anchor set in memory so the service boots with no
 * database. Replaced by a PostgreSQL/R2DBC adapter in US-02 (snapshot persistence).
 */
@Repository
public class InMemoryTrustAnchorRepository implements TrustAnchorRepositoryPort {

    private final AtomicReference<List<TrustAnchor>> anchors = new AtomicReference<>(List.of());

    @Override
    public Mono<Void> replaceAll(List<TrustAnchor> newAnchors) {
        return Mono.fromRunnable(() -> anchors.set(List.copyOf(newAnchors)));
    }

    @Override
    public Flux<TrustAnchor> findAll() {
        return Flux.fromIterable(anchors.get());
    }
}
