package es.in2.trustregistry.anchors.infrastructure.adapter.memory;

import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;
import es.in2.trustregistry.anchors.domain.model.TrustServiceStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    @Test
    void current_ReadConcurrentlyDuringReplaceAll_NeverObservesAMixOfBothSets() throws Exception {
        // Arrange: AC-07 — a concurrent read must return either the full previous set or the
        // full new one, never a hybrid of both.
        TrustAnchorSet previousSet =
                new TrustAnchorSet(List.of(anchor("CN=Previous")), Instant.parse("2026-01-01T00:00:00Z"));
        TrustAnchorSet nextSet =
                new TrustAnchorSet(List.of(anchor("CN=Next")), Instant.parse("2026-01-02T00:00:00Z"));
        repository.replaceAll(previousSet);

        int readerCount = 16;
        int readsPerReader = 10_000;
        CountDownLatch readersReady = new CountDownLatch(readerCount);
        CountDownLatch startSignal = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(readerCount);
        try {
            List<Callable<List<TrustAnchorSet>>> readers = IntStream.range(0, readerCount)
                    .<Callable<List<TrustAnchorSet>>>mapToObj(i -> () -> {
                        readersReady.countDown();
                        startSignal.await();
                        List<TrustAnchorSet> observations = new ArrayList<>(readsPerReader);
                        for (int read = 0; read < readsPerReader; read++) {
                            observations.add(repository.current());
                        }
                        return observations;
                    })
                    .collect(Collectors.toList());
            List<Future<List<TrustAnchorSet>>> futures =
                    readers.stream().map(executor::submit).collect(Collectors.toList());

            // Act
            readersReady.await();
            startSignal.countDown();
            repository.replaceAll(nextSet);

            // Assert
            for (Future<List<TrustAnchorSet>> future : futures) {
                assertThat(future.get()).allSatisfy(observed -> assertThat(observed).isIn(previousSet, nextSet));
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
