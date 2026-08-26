package es.in2.trustregistry.anchors.application;

import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustServiceStatus;
import es.in2.trustregistry.anchors.domain.port.OfficialTrustListPort;
import es.in2.trustregistry.anchors.domain.port.TrustAnchorRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrustAnchorSyncServiceTest {

    private static final Instant EFFECTIVE = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private OfficialTrustListPort officialTrustList;

    @Mock
    private TrustAnchorRepositoryPort repository;

    @Captor
    private ArgumentCaptor<List<TrustAnchor>> storedAnchors;

    private TrustAnchorSyncService service;

    @BeforeEach
    void setUp() {
        service = new TrustAnchorSyncService(officialTrustList, repository);
    }

    private static TrustAnchor anchor(String subject, TrustServiceStatus status) {
        return new TrustAnchor(subject, "pem", "ES", "serviceType", status, EFFECTIVE);
    }

    @Test
    void synchronise_MixedStatuses_StoresOnlyGrantedAnchors() {
        // Arrange
        when(officialTrustList.fetchAnchors()).thenReturn(List.of(
                anchor("CN=Granted", TrustServiceStatus.GRANTED),
                anchor("CN=Withdrawn", TrustServiceStatus.WITHDRAWN),
                anchor("CN=Suspended", TrustServiceStatus.SUSPENDED)));

        // Act
        int stored = service.synchronise();

        // Assert
        assertThat(stored).isEqualTo(1);
        verify(repository).replaceAll(storedAnchors.capture());
        assertThat(storedAnchors.getValue())
                .extracting(TrustAnchor::subject)
                .containsExactly("CN=Granted");
    }

    @Test
    void synchronise_SourceReturnsNothing_StoresAnEmptySet() {
        // Arrange
        when(officialTrustList.fetchAnchors()).thenReturn(List.of());

        // Act
        int stored = service.synchronise();

        // Assert
        assertThat(stored).isZero();
        verify(repository).replaceAll(List.of());
    }

    @Test
    void currentAnchors_RepositoryHoldsAnchors_ReturnsThem() {
        // Arrange
        TrustAnchor granted = anchor("CN=Granted", TrustServiceStatus.GRANTED);
        when(repository.findAll()).thenReturn(List.of(granted));

        // Act
        List<TrustAnchor> anchors = service.currentAnchors();

        // Assert
        assertThat(anchors).containsExactly(granted);
    }
}
