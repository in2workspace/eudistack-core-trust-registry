package es.in2.trustregistry.entities.application;

import es.in2.trustregistry.entities.domain.model.EntityRole;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import es.in2.trustregistry.entities.domain.port.TrustedEntityRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrustedEntityServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");
    private static final String TENANT = "sandbox";
    private static final String ORG_ID = "VATES-B12345678";

    @Mock
    private TrustedEntityRepositoryPort repository;

    private TrustedEntityService service;

    @BeforeEach
    void setUp() {
        service = new TrustedEntityService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void isTrusted_RegisteredEntityWithRoleAndValidWindow_ReturnsTrue() {
        // Arrange
        TrustedEntity entity = new TrustedEntity(TENANT, ORG_ID, "Acme SL",
                Set.of(EntityRole.RELYING_PARTY), "pem", NOW.minusSeconds(60), NOW.plusSeconds(3600));
        when(repository.findByOrganizationIdentifier(TENANT, ORG_ID)).thenReturn(Optional.of(entity));

        // Act
        boolean trusted = service.isTrusted(TENANT, ORG_ID, EntityRole.RELYING_PARTY);

        // Assert
        assertThat(trusted).isTrue();
    }

    @Test
    void isTrusted_RegisteredEntityWithDifferentRole_ReturnsFalse() {
        // Arrange
        TrustedEntity entity = new TrustedEntity(TENANT, ORG_ID, "Acme SL",
                Set.of(EntityRole.WALLET_PROVIDER), "pem", NOW.minusSeconds(60), null);
        when(repository.findByOrganizationIdentifier(TENANT, ORG_ID)).thenReturn(Optional.of(entity));

        // Act
        boolean trusted = service.isTrusted(TENANT, ORG_ID, EntityRole.RELYING_PARTY);

        // Assert
        assertThat(trusted).isFalse();
    }

    @Test
    void isTrusted_ExpiredRegistration_ReturnsFalse() {
        // Arrange
        TrustedEntity entity = new TrustedEntity(TENANT, ORG_ID, "Acme SL",
                Set.of(EntityRole.RELYING_PARTY), "pem", NOW.minusSeconds(7200), NOW.minusSeconds(60));
        when(repository.findByOrganizationIdentifier(TENANT, ORG_ID)).thenReturn(Optional.of(entity));

        // Act
        boolean trusted = service.isTrusted(TENANT, ORG_ID, EntityRole.RELYING_PARTY);

        // Assert
        assertThat(trusted).isFalse();
    }

    @Test
    void isTrusted_UnknownOrganization_ReturnsFalse() {
        // Arrange
        when(repository.findByOrganizationIdentifier(TENANT, ORG_ID)).thenReturn(Optional.empty());

        // Act
        boolean trusted = service.isTrusted(TENANT, ORG_ID, EntityRole.RELYING_PARTY);

        // Assert
        assertThat(trusted).isFalse();
    }

    @Test
    void register_NewEntity_DelegatesToTheRepository() {
        // Arrange
        TrustedEntity entity = new TrustedEntity(TENANT, ORG_ID, "Acme SL",
                Set.of(EntityRole.RELYING_PARTY), "pem", NOW, null);
        when(repository.save(entity)).thenReturn(entity);

        // Act
        TrustedEntity saved = service.register(entity);

        // Assert
        assertThat(saved).isEqualTo(entity);
    }

    @Test
    void list_TenantWithEntities_ReturnsThem() {
        // Arrange
        TrustedEntity entity = new TrustedEntity(TENANT, ORG_ID, "Acme SL",
                Set.of(EntityRole.RELYING_PARTY), "pem", NOW, null);
        when(repository.findAllByTenant(TENANT)).thenReturn(List.of(entity));

        // Act & Assert
        assertThat(service.list(TENANT)).containsExactly(entity);
    }

    @Test
    void revoke_ExistingEntity_DelegatesToTheRepository() {
        // Act
        service.revoke(TENANT, ORG_ID);

        // Assert
        verify(repository).delete(TENANT, ORG_ID);
    }
}
