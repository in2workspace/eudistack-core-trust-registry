package es.in2.trustregistry.anchors.infrastructure.adapter.memory;

import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustServiceStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTrustAnchorRepositoryTest {

    private final InMemoryTrustAnchorRepository repository = new InMemoryTrustAnchorRepository();

    private static TrustAnchor anchor(String subject) {
        return new TrustAnchor(subject, "pem", "ES", "serviceType",
                TrustServiceStatus.GRANTED, Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test
    void findAll_NothingStoredYet_ReturnsEmpty() {
        // Act
        List<TrustAnchor> anchors = repository.findAll();

        // Assert
        assertThat(anchors).isEmpty();
    }

    @Test
    void replaceAll_CalledTwice_KeepsOnlyTheLastSet() {
        // Arrange
        repository.replaceAll(List.of(anchor("CN=First")));

        // Act
        repository.replaceAll(List.of(anchor("CN=Second")));

        // Assert
        assertThat(repository.findAll()).extracting(TrustAnchor::subject).containsExactly("CN=Second");
    }

    @Test
    void replaceAll_CallerMutatesTheSourceList_StoredSetIsUnaffected() {
        // Arrange
        List<TrustAnchor> mutable = new ArrayList<>(List.of(anchor("CN=First")));
        repository.replaceAll(mutable);

        // Act
        mutable.clear();

        // Assert
        assertThat(repository.findAll()).hasSize(1);
    }
}
