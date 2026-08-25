package es.in2.trustregistry.entities.application;

import es.in2.trustregistry.entities.domain.model.EntityRole;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import es.in2.trustregistry.entities.domain.port.TrustedEntityRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

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
        when(repository.findByOrganizationIdentifier(TENANT, ORG_ID)).thenReturn(Mono.just(entity));

        // Act
        Mono<Boolean> result = service.isTrusted(TENANT, ORG_ID, EntityRole.RELYING_PARTY);

        // Assert
        StepVerifier.create(result).expectNext(true).verifyComplete();
    }

    @Test
    void isTrusted_RegisteredEntityWithDifferentRole_ReturnsFalse() {
        // Arrange
        TrustedEntity entity = new TrustedEntity(TENANT, ORG_ID, "Acme SL",
                Set.of(EntityRole.WALLET_PROVIDER), "pem", NOW.minusSeconds(60), null);
        when(repository.findByOrganizationIdentifier(TENANT, ORG_ID)).thenReturn(Mono.just(entity));

        // Act
        Mono<Boolean> result = service.isTrusted(TENANT, ORG_ID, EntityRole.RELYING_PARTY);

        // Assert
        StepVerifier.create(result).expectNext(false).verifyComplete();
    }

    @Test
    void isTrusted_ExpiredRegistration_ReturnsFalse() {
        // Arrange
        TrustedEntity entity = new TrustedEntity(TENANT, ORG_ID, "Acme SL",
                Set.of(EntityRole.RELYING_PARTY), "pem", NOW.minusSeconds(7200), NOW.minusSeconds(60));
        when(repository.findByOrganizationIdentifier(TENANT, ORG_ID)).thenReturn(Mono.just(entity));

        // Act
        Mono<Boolean> result = service.isTrusted(TENANT, ORG_ID, EntityRole.RELYING_PARTY);

        // Assert
        StepVerifier.create(result).expectNext(false).verifyComplete();
    }

    @Test
    void isTrusted_UnknownOrganization_ReturnsFalse() {
        // Arrange
        when(repository.findByOrganizationIdentifier(TENANT, ORG_ID)).thenReturn(Mono.empty());

        // Act
        Mono<Boolean> result = service.isTrusted(TENANT, ORG_ID, EntityRole.RELYING_PARTY);

        // Assert
        StepVerifier.create(result).expectNext(false).verifyComplete();
    }
}
