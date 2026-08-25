package es.in2.trustregistry.entities.infrastructure.adapter.memory;

import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import es.in2.trustregistry.entities.domain.port.TrustedEntityRepositoryPort;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scaffolding adapter: keeps the private list in memory so the service boots with no
 * database. Replaced by a PostgreSQL adapter (schema-per-tenant) in US-03.
 */
@Repository
public class InMemoryTrustedEntityRepository implements TrustedEntityRepositoryPort {

    private final Map<String, TrustedEntity> store = new ConcurrentHashMap<>();

    private static String key(String tenantId, String organizationIdentifier) {
        return tenantId + "::" + organizationIdentifier;
    }

    @Override
    public TrustedEntity save(TrustedEntity entity) {
        store.put(key(entity.tenantId(), entity.organizationIdentifier()), entity);
        return entity;
    }

    @Override
    public Optional<TrustedEntity> findByOrganizationIdentifier(String tenantId, String organizationIdentifier) {
        return Optional.ofNullable(store.get(key(tenantId, organizationIdentifier)));
    }

    @Override
    public List<TrustedEntity> findAllByTenant(String tenantId) {
        return store.values().stream()
                .filter(entity -> entity.tenantId().equals(tenantId))
                .toList();
    }

    @Override
    public void delete(String tenantId, String organizationIdentifier) {
        store.remove(key(tenantId, organizationIdentifier));
    }
}
