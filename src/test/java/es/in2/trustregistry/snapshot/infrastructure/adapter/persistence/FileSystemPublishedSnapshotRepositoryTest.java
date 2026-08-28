package es.in2.trustregistry.snapshot.infrastructure.adapter.persistence;

import es.in2.trustregistry.shared.infrastructure.config.TrustRegistryProperties;
import es.in2.trustregistry.snapshot.domain.model.PublishedSnapshot;
import es.in2.trustregistry.snapshot.domain.model.SnapshotFingerprint;
import es.in2.trustregistry.snapshot.domain.model.TrustProfile;
import es.in2.trustregistry.snapshot.domain.port.PublishedSnapshotRepositoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the two properties task 13's Javadoc claims: durability across a fresh instance
 * (EC-04, NFR-R-228-01) and a compare-and-swap that is genuinely atomic under concurrency for
 * the same tenant (EC-03, RC-1), not merely correct when exercised single-threaded.
 */
class FileSystemPublishedSnapshotRepositoryTest {

    @TempDir
    Path cacheDirectory;

    private static PublishedSnapshot snapshot(String tenantId, long version, String fingerprintValue) {
        return new PublishedSnapshot(tenantId, version, new SnapshotFingerprint(fingerprintValue), "jws-" + version);
    }

    private FileSystemPublishedSnapshotRepository newRepository() {
        TrustRegistryProperties properties = new TrustRegistryProperties(
                "https://example.org/lotl.xml",
                "classpath:oj-keystore.p12",
                cacheDirectory.toString(),
                3600L,
                Duration.ofHours(24),
                new TrustRegistryProperties.Sync(Duration.ofSeconds(1), Duration.ofMinutes(1)),
                new TrustRegistryProperties.Signing("classpath:signing.p12", "password", "alias"),
                TrustProfile.PRODUCTION);
        return new FileSystemPublishedSnapshotRepository(properties);
    }

    @Test
    void findByTenant_NeverPublished_IsEmpty() {
        // Arrange
        FileSystemPublishedSnapshotRepository repository = newRepository();

        // Act
        Optional<PublishedSnapshot> result = repository.findByTenant("cgcom");

        // Assert
        assertThat(result).isEmpty();
    }

    @Test
    void replaceIfVersionIs_NoPriorPublication_AppliesAndPersists() {
        // Arrange
        FileSystemPublishedSnapshotRepository repository = newRepository();
        PublishedSnapshot candidate = snapshot("cgcom", 1L, "fp-1");

        // Act
        PublishedSnapshotRepositoryPort.CasResult result = repository.replaceIfVersionIs("cgcom", 0L, candidate);

        // Assert
        assertThat(result).isInstanceOf(PublishedSnapshotRepositoryPort.CasResult.Applied.class);
        assertThat(repository.findByTenant("cgcom")).contains(candidate);
    }

    @Test
    void replaceIfVersionIs_StaleExpectedVersion_ReportsConflictWithCurrentPublication() {
        // Arrange
        FileSystemPublishedSnapshotRepository repository = newRepository();
        PublishedSnapshot first = snapshot("cgcom", 1L, "fp-1");
        repository.replaceIfVersionIs("cgcom", 0L, first);
        PublishedSnapshot staleCandidate = snapshot("cgcom", 2L, "fp-2");

        // Act
        PublishedSnapshotRepositoryPort.CasResult result =
                repository.replaceIfVersionIs("cgcom", 0L, staleCandidate);

        // Assert
        assertThat(result).isInstanceOf(PublishedSnapshotRepositoryPort.CasResult.Conflict.class);
        assertThat(((PublishedSnapshotRepositoryPort.CasResult.Conflict) result).current()).isEqualTo(first);
        assertThat(repository.findByTenant("cgcom")).contains(first);
    }

    @Test
    void replaceIfVersionIs_NonZeroExpectedVersionWithNoPriorPublication_RejectsTheCallAsAContractViolation() {
        // Arrange: PublishedSnapshotRepositoryPort's contract requires expectedVersion=0 when the
        // caller observed no prior publication; a caller passing anything else here has not
        // actually derived expectedVersion from this port's own findByTenant(), which is a caller
        // bug rather than a race this adapter should paper over.
        FileSystemPublishedSnapshotRepository repository = newRepository();

        // Act & Assert
        assertThatThrownBy(() -> repository.replaceIfVersionIs("cgcom", 1L, snapshot("cgcom", 2L, "fp-2")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findByTenant_NewInstanceOverSameDirectory_SurvivesRestart() {
        // Arrange: a fresh repository instance over the same cacheDirectory simulates a process
        // restart at the unit level (task 22 covers the same guarantee at container level).
        FileSystemPublishedSnapshotRepository firstProcess = newRepository();
        PublishedSnapshot published = snapshot("cgcom", 7L, "fp-7");
        firstProcess.replaceIfVersionIs("cgcom", 0L, published);

        // Act
        FileSystemPublishedSnapshotRepository restarted = newRepository();

        // Assert
        assertThat(restarted.findByTenant("cgcom")).contains(published);
    }

    @Test
    void replaceIfVersionIs_ConcurrentAttemptsWithTheSameExpectedVersion_AppliesExactlyOnce()
            throws Exception {
        // Arrange: EC-03 — many threads observed the same prior state (no publication yet, so
        // every one of them computed expectedVersion=0, exactly as SnapshotVersionResolver would
        // after each independently called findByTenant()) and race to seal version 1 at the same
        // time. Exactly one may apply; every other one must see Conflict reporting that winner,
        // never a lost update and never two writers both observing Applied.
        FileSystemPublishedSnapshotRepository repository = newRepository();
        String tenantId = "cgcom";
        int attempts = 64;
        CountDownLatch startSignal = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(16);
        AtomicInteger appliedCount = new AtomicInteger();
        try {
            List<Callable<PublishedSnapshotRepositoryPort.CasResult>> tasks = IntStream.range(0, attempts)
                    .<Callable<PublishedSnapshotRepositoryPort.CasResult>>mapToObj(attempt -> () -> {
                        startSignal.await();
                        PublishedSnapshot candidate = snapshot(tenantId, 1L, "fp-from-attempt-" + attempt);
                        PublishedSnapshotRepositoryPort.CasResult result =
                                repository.replaceIfVersionIs(tenantId, 0L, candidate);
                        if (result instanceof PublishedSnapshotRepositoryPort.CasResult.Applied) {
                            appliedCount.incrementAndGet();
                        }
                        return result;
                    })
                    .collect(Collectors.toList());
            List<Future<PublishedSnapshotRepositoryPort.CasResult>> futures =
                    tasks.stream().map(executor::submit).collect(Collectors.toList());

            // Act
            startSignal.countDown();
            List<PublishedSnapshotRepositoryPort.CasResult> results = new ArrayList<>();
            for (Future<PublishedSnapshotRepositoryPort.CasResult> future : futures) {
                results.add(future.get());
            }

            // Assert: exactly one Applied, and every Conflict reports the very publication that
            // won — no attempt observes "no current publication" once one writer has applied.
            assertThat(appliedCount).hasValue(1);
            PublishedSnapshot winner = repository.findByTenant(tenantId).orElseThrow();
            assertThat(winner.version()).isEqualTo(1L);
            long conflictCount = results.stream()
                    .filter(PublishedSnapshotRepositoryPort.CasResult.Conflict.class::isInstance)
                    .map(PublishedSnapshotRepositoryPort.CasResult.Conflict.class::cast)
                    .peek(conflict -> assertThat(conflict.current()).isEqualTo(winner))
                    .count();
            assertThat(conflictCount).isEqualTo(attempts - 1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void replaceIfVersionIs_SequentialContentionOnSameTenant_NeverLosesAnUpdate() throws Exception {
        // Arrange: a more realistic contention shape than the previous test — every thread reads
        // the current state, computes the next candidate from it (as SnapshotVersionResolver
        // does), and retries once on conflict by re-reading the winner. This exercises the actual
        // read-compare-write sequence the in-process lock protects, not just isolated CAS calls.
        FileSystemPublishedSnapshotRepository repository = newRepository();
        String tenantId = "cgcom";
        int writerCount = 32;
        CountDownLatch startSignal = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writerCount);
        try {
            List<Callable<Void>> writers = IntStream.range(0, writerCount)
                    .<Callable<Void>>mapToObj(writerId -> () -> {
                        startSignal.await();
                        publishWithRetry(repository, tenantId, "writer-" + writerId);
                        return null;
                    })
                    .collect(Collectors.toList());
            List<Future<Void>> futures = writers.stream().map(executor::submit).collect(Collectors.toList());

            // Act
            startSignal.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }

            // Assert: the final version equals exactly the number of writers that ever
            // successfully applied — no version was skipped and none was double-sealed.
            Optional<PublishedSnapshot> finalState = repository.findByTenant(tenantId);
            assertThat(finalState).isPresent();
            assertThat(finalState.get().version()).isEqualTo(writerCount);
        } finally {
            executor.shutdownNow();
        }
    }

    private void publishWithRetry(FileSystemPublishedSnapshotRepository repository, String tenantId, String marker) {
        while (true) {
            Optional<PublishedSnapshot> current = repository.findByTenant(tenantId);
            long expectedVersion = current.map(PublishedSnapshot::version).orElse(0L);
            PublishedSnapshot candidate =
                    snapshot(tenantId, expectedVersion + 1, marker + "-" + (expectedVersion + 1));
            PublishedSnapshotRepositoryPort.CasResult result =
                    repository.replaceIfVersionIs(tenantId, expectedVersion, candidate);
            if (result instanceof PublishedSnapshotRepositoryPort.CasResult.Applied) {
                return;
            }
        }
    }

    @Test
    void replaceIfVersionIs_DifferentTenantsConcurrently_DoNotInterfereWithEachOther() throws Exception {
        // Arrange: AD-5/AC-08 isolation — locking is per-tenant, so publishing for "cgcom" and
        // "kpmg" concurrently must not affect one another's outcome.
        FileSystemPublishedSnapshotRepository repository = newRepository();
        Set<String> tenants = Set.of("cgcom", "kpmg");
        CountDownLatch startSignal = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(tenants.size());
        try {
            List<Callable<PublishedSnapshotRepositoryPort.CasResult>> tasks = tenants.stream()
                    .<Callable<PublishedSnapshotRepositoryPort.CasResult>>map(tenantId -> () -> {
                        startSignal.await();
                        return repository.replaceIfVersionIs(tenantId, 0L, snapshot(tenantId, 1L, "fp-" + tenantId));
                    })
                    .collect(Collectors.toList());
            List<Future<PublishedSnapshotRepositoryPort.CasResult>> futures =
                    tasks.stream().map(executor::submit).collect(Collectors.toList());

            // Act
            startSignal.countDown();

            // Assert
            for (Future<PublishedSnapshotRepositoryPort.CasResult> future : futures) {
                assertThat(future.get()).isInstanceOf(PublishedSnapshotRepositoryPort.CasResult.Applied.class);
            }
            for (String tenantId : tenants) {
                assertThat(repository.findByTenant(tenantId)).isPresent()
                        .get()
                        .extracting(PublishedSnapshot::tenantId)
                        .isEqualTo(tenantId);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
