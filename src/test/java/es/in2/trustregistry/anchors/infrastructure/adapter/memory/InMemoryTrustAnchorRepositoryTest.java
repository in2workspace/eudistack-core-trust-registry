package es.in2.trustregistry.anchors.infrastructure.adapter.memory;

import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;
import es.in2.trustregistry.anchors.domain.model.TrustServiceStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTrustAnchorRepositoryTest {

    private final InMemoryTrustAnchorRepository repository = new InMemoryTrustAnchorRepository();

    private static TrustAnchor anchor(String subject) {
        return new TrustAnchor(subject, "pem", "ES", "serviceType",
                TrustServiceStatus.GRANTED, Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test
    void current_NothingStoredYet_IsNeverSynced() {
        // Act
        TrustAnchorSet current = repository.current();

        // Assert
        assertThat(current.isNeverSynced()).isTrue();
        assertThat(current.anchors()).isEmpty();
    }

    @Test
    void replaceAll_CalledTwice_KeepsOnlyTheLastSet() {
        // Arrange
        Instant firstSync = Instant.parse("2026-01-01T00:00:00Z");
        Instant secondSync = Instant.parse("2026-01-02T00:00:00Z");
        repository.replaceAll(new TrustAnchorSet(List.of(anchor("CN=First")), firstSync));

        // Act
        repository.replaceAll(new TrustAnchorSet(List.of(anchor("CN=Second")), secondSync));

        // Assert
        TrustAnchorSet current = repository.current();
        assertThat(current.anchors()).extracting(TrustAnchor::subject).containsExactly("CN=Second");
        assertThat(current.lastSuccessfulSyncAt()).isEqualTo(secondSync);
    }
}
