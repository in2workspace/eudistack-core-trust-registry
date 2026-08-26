package es.in2.trustregistry.entities.application;

import es.in2.trustregistry.entities.domain.model.EntityRole;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import es.in2.trustregistry.entities.domain.port.TrustedEntityRepositoryPort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

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

    public boolean isTrusted(String tenantId, String organizationIdentifier, EntityRole role) {
        Instant now = Instant.now(clock);
        return repository.findByOrganizationIdentifier(tenantId, organizationIdentifier)
                .map(entity -> entity.isActiveAt(now) && entity.hasRole(role))
                .orElse(false);
    }

    /**
     * Applies one entry of the provisioned configuration. Reachable only from the
     * configuration loader, never from an HTTP endpoint — see AD-9.
     */
    public TrustedEntity apply(TrustedEntity entity) {
        return repository.save(entity);
    }

    public List<TrustedEntity> list(String tenantId) {
        return repository.findAllByTenant(tenantId);
    }

    /**
     * Drops an entry that the provisioned configuration no longer contains.
     * Reachable only from the configuration loader — see AD-9.
     */
    public void withdraw(String tenantId, String organizationIdentifier) {
        repository.delete(tenantId, organizationIdentifier);
    }
}
