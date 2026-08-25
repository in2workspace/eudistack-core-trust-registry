package es.in2.trustregistry.anchors.domain.port;

import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Driven port: local persistence of the anchors resolved from official sources. */
public interface TrustAnchorRepositoryPort {

    Mono<Void> replaceAll(java.util.List<TrustAnchor> anchors);

    Flux<TrustAnchor> findAll();
}
