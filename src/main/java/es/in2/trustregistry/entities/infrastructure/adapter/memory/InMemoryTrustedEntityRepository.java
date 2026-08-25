package es.in2.trustregistry.entities.infrastructure.adapter.memory;

import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import es.in2.trustregistry.entities.domain.port.TrustedEntityRepositoryPort;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scaffolding adapter: keeps the private list in memory so the service boots with no
 * database. Replaced by a PostgreSQL/R2DBC adapter (schema-per-tenant) in US-03.
 */
@Repository
public class InMemoryTrustedEntityRepository implements TrustedEntityRepositoryPort {

    private final Map<String, TrustedEntity> store = new ConcurrentHashMap<>();

    private static String key(String tenantId, String organizationIdentifier) {
        return tenantId + "::" + organizationIdentifier;
    }

    @Override
    public Mono<TrustedEntity> save(TrustedEntity entity) {
        return Mono.fromCallable(() -> {
            store.put(key(entity.tenantId(), entity.organizationIdentifier()), entity);
            return entity;
        });
    }

    @Override
    public Mono<TrustedEntity> findByOrganizationIdentifier(String tenantId, String organizationIdentifier) {
        return Mono.justOrEmpty(store.get(key(tenantId, organizationIdentifier)));
    }

    @Override
    public Flux<TrustedEntity> findAllByTenant(String tenantId) {
        return Flux.fromIterable(store.values())
                .filter(entity -> entity.tenantId().equals(tenantId));
    }

    @Override
    public Mono<Void> delete(String tenantId, String organizationIdentifier) {
        return Mono.fromRunnable(() -> store.remove(key(tenantId, organizationIdentifier)));
    }
}
