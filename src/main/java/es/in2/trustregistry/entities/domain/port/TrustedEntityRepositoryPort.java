package es.in2.trustregistry.entities.domain.port;

import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Driven port: persistence of the private List of Trusted Entities, scoped per tenant. */
public interface TrustedEntityRepositoryPort {

    Mono<TrustedEntity> save(TrustedEntity entity);

    Mono<TrustedEntity> findByOrganizationIdentifier(String tenantId, String organizationIdentifier);

    Flux<TrustedEntity> findAllByTenant(String tenantId);

    Mono<Void> delete(String tenantId, String organizationIdentifier);
}
