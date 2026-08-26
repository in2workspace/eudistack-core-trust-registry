package es.in2.trustregistry.entities.infrastructure.adapter.memory;

import es.in2.trustregistry.entities.domain.model.EntityRole;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTrustedEntityRepositoryTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");

    private final InMemoryTrustedEntityRepository repository = new InMemoryTrustedEntityRepository();

    private static TrustedEntity entity(String tenantId, String organizationIdentifier) {
        return new TrustedEntity(tenantId, organizationIdentifier, "Acme SL",
                Set.of(EntityRole.RELYING_PARTY), "pem", FROM, null);
    }

    @Test
    void findByOrganizationIdentifier_SavedInAnotherTenant_ReturnsEmpty() {
        // Arrange
        repository.save(entity("tenant-a", "VATES-B1"));

        // Act & Assert
        assertThat(repository.findByOrganizationIdentifier("tenant-b", "VATES-B1")).isEmpty();
        assertThat(repository.findByOrganizationIdentifier("tenant-a", "VATES-B1")).isPresent();
    }

    @Test
    void findAllByTenant_EntitiesInSeveralTenants_ReturnsOnlyTheOnesOfThatTenant() {
        // Arrange
        repository.save(entity("tenant-a", "VATES-B1"));
        repository.save(entity("tenant-a", "VATES-B2"));
        repository.save(entity("tenant-b", "VATES-B3"));

        // Act & Assert
        assertThat(repository.findAllByTenant("tenant-a"))
                .extracting(TrustedEntity::organizationIdentifier)
                .containsExactlyInAnyOrder("VATES-B1", "VATES-B2");
    }

    @Test
    void save_SameIdentifierTwice_OverwritesTheEntry() {
        // Arrange
        repository.save(entity("tenant-a", "VATES-B1"));
        TrustedEntity renamed = new TrustedEntity("tenant-a", "VATES-B1", "Acme Holding SL",
                Set.of(EntityRole.WALLET_PROVIDER), "pem", FROM, null);

        // Act
        repository.save(renamed);

        // Assert
        assertThat(repository.findAllByTenant("tenant-a")).hasSize(1);
        assertThat(repository.findByOrganizationIdentifier("tenant-a", "VATES-B1"))
                .get()
                .extracting(TrustedEntity::legalName)
                .isEqualTo("Acme Holding SL");
    }

    @Test
    void delete_ExistingEntity_RemovesItFromThatTenantOnly() {
        // Arrange
        repository.save(entity("tenant-a", "VATES-B1"));
        repository.save(entity("tenant-b", "VATES-B1"));

        // Act
        repository.delete("tenant-a", "VATES-B1");

        // Assert
        assertThat(repository.findByOrganizationIdentifier("tenant-a", "VATES-B1")).isEmpty();
        assertThat(repository.findByOrganizationIdentifier("tenant-b", "VATES-B1")).isPresent();
    }
}
