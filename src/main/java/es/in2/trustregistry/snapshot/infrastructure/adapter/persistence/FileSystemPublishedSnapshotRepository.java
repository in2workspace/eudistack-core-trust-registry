package es.in2.trustregistry.snapshot.infrastructure.adapter.persistence;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import es.in2.trustregistry.shared.infrastructure.config.TrustRegistryProperties;
import es.in2.trustregistry.snapshot.domain.model.PublishedSnapshot;
import es.in2.trustregistry.snapshot.domain.model.SnapshotFingerprint;
import es.in2.trustregistry.snapshot.domain.port.PublishedSnapshotRepositoryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * File-based adapter of {@link PublishedSnapshotRepositoryPort} (AD-3): one JSON document per
 * tenant under {@code <cacheDirectory>/snapshots/}, using the same disk cache volume {@code
 * DssTrustListJobConfig} already relies on for offline startup (task 15 introduces no new
 * configuration for this). A dedicated {@code snapshots} subdirectory keeps these files
 * separate from DSS's own trusted-list cache entries living directly under {@code
 * cacheDirectory}.
 *
 * <p><strong>Atomic write.</strong> Every publish writes to a temporary file in the same
 * directory as the target — guaranteeing the same filesystem — then commits with {@link
 * Files#move} using {@link StandardCopyOption#ATOMIC_MOVE}. This is what makes {@code
 * NFR-R-228-01} hold across a crash: a process that dies mid-write never leaves a partially
 * written file where the previous publication used to be, because the target path is never
 * opened for writing directly.
 *
 * <p><strong>Compare-and-swap under AD-3's single-instance constraint.</strong> {@code
 * technical-design.md} AD-3 says the atomic rename "da el CAS de RC-1 en instancia única", but
 * atomicity of the final rename is not, by itself, a compare-and-swap: the read of the current
 * version and the decision to write happen as separate filesystem operations before that
 * rename, which is exactly the TOCTOU window RC-1 needs closed for two concurrent requests
 * against the <em>same tenant</em> in the <em>same JVM</em>. AD-3 already accepts single-instance
 * as the scope of this persistence, which is precisely what makes an in-process lock a complete
 * fix rather than a partial one: with only one JVM ever touching these files, a per-tenant
 * {@link ReentrantLock} guarding the read-compare-write sequence closes the window entirely,
 * with no dependency on filesystem-level locking or a database transaction. Locking is
 * per-tenant, not a single repository-wide lock, so that two tenants publishing concurrently
 * never serialise against each other (AD-5, AC-08 isolation) — {@link #findByTenant} takes no
 * lock at all, since a file that is only ever replaced by atomic rename is always read as one
 * complete, self-consistent document, the same argument {@code InMemoryTrustAnchorRepository}
 * already relies on for its {@code AtomicReference}.
 *
 * <p><strong>Corrupted or unreadable publication.</strong> Neither {@code acceptance-criteria.md}
 * nor {@code technical-design.md} says what to do if a tenant's file exists but cannot be parsed
 * (truncated content, foreign tampering — not reachable via this adapter's own write path, which
 * never exposes a partially written file). This is treated as a fail-fast infrastructure error,
 * not as "never published": silently falling back to an empty result would let the published
 * version regress after external corruption, which is exactly what {@code NFR-R-228-01} rules
 * out.
 */
@Slf4j
@Repository
public class FileSystemPublishedSnapshotRepository implements PublishedSnapshotRepositoryPort {

    private static final String SNAPSHOTS_SUBDIRECTORY = "snapshots";
    private static final String FILE_SUFFIX = ".json";
    private static final String TEMP_FILE_INFIX = ".tmp-";

    /**
     * {@code quality-report.md} B4 (CRITICAL): Jackson's default {@code
     * StreamReadConstraints.getMaxStringLength()} is 20,000,000 characters. A real snapshot
     * against the live EU LOTL (10,389 anchors) produces a {@code signedDocument} JWS of ~33 MB
     * on its own, well past that default — {@code write()} persists it as valid JSON without
     * complaint, but every subsequent {@code read()} then throws {@code
     * StreamConstraintsException}, which this class's own deliberate fail-fast policy turns into
     * an {@code UncheckedIOException} (see the class Javadoc) — a tenant's very first successful
     * publish leaves it in a permanent HTTP 500 until someone deletes the file by hand. 100 MB
     * gives real-world headroom over the ~33 MB observed today (the LOTL only grows) while still
     * being a bound, not "unlimited" — an unbounded string length would reopen, at the JSON
     * parsing layer, exactly the kind of unbounded-resource risk {@code S3}/{@code TD-05} closed
     * for the in-memory structures indexed by tenant.
     */
    private static final int MAX_PERSISTED_STRING_LENGTH = 100_000_000;

    private final Path snapshotsDirectory;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, Lock> tenantLocks = new ConcurrentHashMap<>();

    public FileSystemPublishedSnapshotRepository(TrustRegistryProperties properties) {
        this.snapshotsDirectory = Path.of(properties.cacheDirectory(), SNAPSHOTS_SUBDIRECTORY);
        JsonFactory jsonFactory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxStringLength(MAX_PERSISTED_STRING_LENGTH)
                        .build())
                .build();
        this.objectMapper = new ObjectMapper(jsonFactory);
        createDirectoryIfMissing(snapshotsDirectory);
    }

    @Override
    public Optional<PublishedSnapshot> findByTenant(String tenantId) {
        Path file = fileFor(tenantId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(read(file));
    }

    @Override
    public CasResult replaceIfVersionIs(String tenantId, long expectedVersion, PublishedSnapshot candidate) {
        Lock lock = tenantLocks.computeIfAbsent(tenantId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            Optional<PublishedSnapshot> current = findByTenant(tenantId);
            long currentVersion = current.map(PublishedSnapshot::version).orElse(0L);

            if (currentVersion != expectedVersion) {
                return new CasResult.Conflict(current.orElseThrow(() -> new IllegalArgumentException(
                        "expectedVersion=" + expectedVersion + " was passed for tenant '" + tenantId
                                + "' but no publication exists yet for it; a caller following this port's "
                                + "contract must pass 0 when it observed no prior publication (see "
                                + "PublishedSnapshotRepositoryPort#replaceIfVersionIs Javadoc)")));
            }

            write(candidate);
            return new CasResult.Applied(candidate);
        } finally {
            lock.unlock();
        }
    }

    private PublishedSnapshot read(Path file) {
        try {
            PersistedForm persisted = objectMapper.readValue(file.toFile(), PersistedForm.class);
            return persisted.toDomain();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot read published snapshot file '" + file + "'; treating this as a fail-fast "
                            + "infrastructure error rather than an absent publication, so a corrupted file "
                            + "never regresses the published version", e);
        }
    }

    private void write(PublishedSnapshot snapshot) {
        Path target = fileFor(snapshot.tenantId());
        Path temp = snapshotsDirectory.resolve(target.getFileName() + TEMP_FILE_INFIX + System.nanoTime());
        try {
            try (var out = Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                objectMapper.writeValue(out, PersistedForm.from(snapshot));
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot persist published snapshot for tenant '" + snapshot.tenantId() + "'", e);
        } finally {
            deleteIfExists(temp);
        }
    }

    private Path fileFor(String tenantId) {
        if (tenantId == null || tenantId.isBlank()
                || tenantId.contains("/") || tenantId.contains("\\") || tenantId.contains("..")) {
            throw new IllegalArgumentException("tenantId is not a valid file name component: '" + tenantId + "'");
        }
        return snapshotsDirectory.resolve(tenantId + FILE_SUFFIX);
    }

    private static void createDirectoryIfMissing(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Cannot create the published snapshot directory '" + directory + "'", e);
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // Best-effort cleanup only: the temp file was never renamed to the target, so leaving
            // it behind cannot corrupt a published snapshot. Not logged at error level to avoid
            // noise on transient filesystem contention.
            log.debug("Could not delete temporary file '{}'", path, e);
        }
    }

    /**
     * On-disk shape of {@link PublishedSnapshot}, kept as a private mapping rather than annotating
     * the domain record directly: {@code snapshot/domain} stays free of Jackson, and this
     * adapter's storage format can evolve independently of the aggregate's Java shape.
     */
    private record PersistedForm(String tenantId, long version, String fingerprint, String signedDocument) {

        static PersistedForm from(PublishedSnapshot snapshot) {
            return new PersistedForm(
                    snapshot.tenantId(), snapshot.version(), snapshot.fingerprint().value(), snapshot.signedDocument());
        }

        PublishedSnapshot toDomain() {
            return new PublishedSnapshot(tenantId, version, new SnapshotFingerprint(fingerprint), signedDocument);
        }
    }
}
