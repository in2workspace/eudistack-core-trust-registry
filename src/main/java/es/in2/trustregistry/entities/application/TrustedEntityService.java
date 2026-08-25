package es.in2.trustregistry.entities.application;

import es.in2.trustregistry.entities.domain.model.EntityRole;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import es.in2.trustregistry.entities.domain.port.TrustedEntityRepositoryPort;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;

/**
 * Answers the question the whole platform asks: is this organisation trusted, right now,
 * for this role, inside this tenant. Fail closed: an unknown organisation is never trusted.
 */
@Service
public class TrustedEntityService {

    private final TrustedEntityRepositoryPort repository;
    private final Clock clock;

    public TrustedEntityService(TrustedEntityRepositoryPort repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public Mono<Boolean> isTrusted(String tenantId, String organizationIdentifier, EntityRole role) {
        Instant now = Instant.now(clock);
        return repository.findByOrganizationIdentifier(tenantId, organizationIdentifier)
                .map(entity -> entity.isActiveAt(now) && entity.hasRole(role))
                .defaultIfEmpty(false);
    }

    public Mono<TrustedEntity> register(TrustedEntity entity) {
        return repository.save(entity);
    }

    public Flux<TrustedEntity> list(String tenantId) {
        return repository.findAllByTenant(tenantId);
    }

    public Mono<Void> revoke(String tenantId, String organizationIdentifier) {
        return repository.delete(tenantId, organizationIdentifier);
    }
}
