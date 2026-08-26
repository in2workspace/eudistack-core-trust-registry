package es.in2.trustregistry.snapshot.application;

import es.in2.trustregistry.anchors.application.TrustAnchorSyncService;
import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustServiceStatus;
import es.in2.trustregistry.entities.application.TrustedEntityService;
import es.in2.trustregistry.entities.domain.model.EntityRole;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import es.in2.trustregistry.shared.infrastructure.config.TrustRegistryProperties;
import es.in2.trustregistry.snapshot.domain.model.TrustSnapshot;
import es.in2.trustregistry.snapshot.domain.port.SnapshotSignerPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrustSnapshotServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-25T10:00:00Z");
    private static final String TENANT = "sandbox";

    @Mock
    private TrustAnchorSyncService anchorService;

    @Mock
    private TrustedEntityService entityService;

    @Mock
    private SnapshotSignerPort signer;

    private TrustSnapshotService service;

    @BeforeEach
    void setUp() {
        TrustRegistryProperties properties = new TrustRegistryProperties(
                "https://ec.europa.eu/tools/lotl/eu-lotl.xml", "classpath:keystore/oj-keystore.p12",
                "/var/cache/trust-registry", 86400);
        service = new TrustSnapshotService(anchorService, entityService, signer, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static TrustAnchor anchor() {
        return new TrustAnchor("CN=Test CA", "pem", "ES", "serviceType",
                TrustServiceStatus.GRANTED, NOW.minusSeconds(86400));
    }

    private static TrustedEntity entity() {
        return new TrustedEntity(TENANT, "VATES-B1", "Acme SL",
                Set.of(EntityRole.RELYING_PARTY), "pem", NOW.minusSeconds(60), null);
    }

    @Test
    void build_AnchorsAndEntitiesAvailable_CombinesBothSourcesForTheTenant() {
        // Arrange
        when(anchorService.currentAnchors()).thenReturn(List.of(anchor()));
        when(entityService.list(TENANT)).thenReturn(List.of(entity()));

        // Act
        TrustSnapshot snapshot = service.build(TENANT);

        // Assert
        assertThat(snapshot.tenantId()).isEqualTo(TENANT);
        assertThat(snapshot.anchors()).hasSize(1);
        assertThat(snapshot.entities()).hasSize(1);
    }

    @Test
    void build_AnySnapshot_StampsGenerationInstantAndConfiguredValidity() {
        // Arrange
        when(anchorService.currentAnchors()).thenReturn(List.of());
        when(entityService.list(TENANT)).thenReturn(List.of());

        // Act
        TrustSnapshot snapshot = service.build(TENANT);

        // Assert
        assertThat(snapshot.generatedAt()).isEqualTo(NOW);
        assertThat(snapshot.timeToLiveSeconds()).isEqualTo(86400);
        assertThat(snapshot.expiresAt()).isEqualTo(NOW.plusSeconds(86400));
    }

    @Test
    void build_CalledRepeatedly_IncrementsTheVersionMonotonically() {
        // Arrange
        when(anchorService.currentAnchors()).thenReturn(List.of());
        when(entityService.list(TENANT)).thenReturn(List.of());

        // Act
        long first = service.build(TENANT).version();
        long second = service.build(TENANT).version();

        // Assert
        assertThat(second).isGreaterThan(first);
    }

    @Test
    void buildSigned_AnySnapshot_ReturnsWhatTheSignerProduced() {
        // Arrange
        when(anchorService.currentAnchors()).thenReturn(List.of());
        when(entityService.list(TENANT)).thenReturn(List.of());
        when(signer.sign(org.mockito.ArgumentMatchers.any(TrustSnapshot.class))).thenReturn("header.payload.signature");

        // Act
        String signed = service.buildSigned(TENANT);

        // Assert
        assertThat(signed).isEqualTo("header.payload.signature");
    }
}
