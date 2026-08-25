package es.in2.trustregistry.entities.domain.model;

import java.time.Instant;
import java.util.Set;

/**
 * An entity registered in the private List of Trusted Entities of one tenant.
 *
 * @param tenantId tenant owning the list; trust never crosses tenant boundaries
 * @param organizationIdentifier value of OID 2.5.4.97 in the entity certificate
 * @param legalName legal name as registered
 * @param roles roles the entity is authorised to play
 * @param certificatePem the entity certificate, PEM encoded
 * @param validFrom instant the registration became effective
 * @param validUntil instant the registration expires, or null if open ended
 */
public record TrustedEntity(
        String tenantId,
        String organizationIdentifier,
        String legalName,
        Set<EntityRole> roles,
        String certificatePem,
        Instant validFrom,
        Instant validUntil
) {

    public boolean isActiveAt(Instant moment) {
        boolean started = validFrom == null || !moment.isBefore(validFrom);
        boolean notExpired = validUntil == null || moment.isBefore(validUntil);
        return started && notExpired;
    }

    public boolean hasRole(EntityRole role) {
        return roles != null && roles.contains(role);
    }
}
