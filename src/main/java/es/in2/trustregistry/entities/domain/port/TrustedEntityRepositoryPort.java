package es.in2.trustregistry.entities.domain.port;

import es.in2.trustregistry.entities.domain.model.TrustedEntity;

import java.util.List;
import java.util.Optional;

/** Driven port: persistence of the private List of Trusted Entities, scoped per tenant. */
public interface TrustedEntityRepositoryPort {

    TrustedEntity save(TrustedEntity entity);

    Optional<TrustedEntity> findByOrganizationIdentifier(String tenantId, String organizationIdentifier);

    List<TrustedEntity> findAllByTenant(String tenantId);

    void delete(String tenantId, String organizationIdentifier);
}
