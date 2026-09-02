package es.in2.trustregistry.snapshot.application;

import es.in2.trustregistry.anchors.application.TrustAnchorSyncService;
import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;
import es.in2.trustregistry.entities.application.TrustedEntityService;
import es.in2.trustregistry.entities.domain.model.TrustedEntity;
import es.in2.trustregistry.shared.infrastructure.config.TrustRegistryProperties;
import es.in2.trustregistry.snapshot.domain.model.PublishedSnapshot;
import es.in2.trustregistry.snapshot.domain.model.SnapshotFingerprint;
import es.in2.trustregistry.snapshot.domain.model.TrustProfile;
import es.in2.trustregistry.snapshot.domain.model.TrustSnapshot;
import es.in2.trustregistry.snapshot.domain.port.PublishedSnapshotRepositoryPort;
import es.in2.trustregistry.snapshot.domain.port.SnapshotPublicationObserverPort;
import es.in2.trustregistry.snapshot.domain.port.SnapshotSignerPort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Publishes the trust snapshot for a tenant: combines the official anchors (global) with the
 * tenant's private entities, versions the result by content (AD-1) and signs only when the
 * content actually changed.
 *
 * <p>The heavy lifting of "does this need a new version, and is signing safe to skip" is
 * {@link SnapshotVersionResolver}'s job (task 8); this service's job is to gather the two
 * sources for one tenant, resolve the fields that need application-layer knowledge
 * ({@code officialTrustStale} needs {@link TrustRegistryProperties#maxAge()}, which the domain
 * does not have access to — see {@code TrustSnapshot}'s Javadoc), and hand the resolver
 * everything it needs to build and sign a candidate on demand.
 *
 * <p>{@code build(String)} is deliberately not reintroduced here: {@link PublishedSnapshot}
 * (task 4) stores only the signed document, not the {@link TrustSnapshot} it was built from, so
 * there is no spec-faithful unsigned "current snapshot" to hand back once a version has been
 * resolved. {@code GET /trust/v1/snapshot/plain}, the endpoint that used to call it, is retired
 * outside the {@code DEVELOPMENT} profile by {@code AD-6} in the next task ({@code
 * TrustSnapshotController}, task 14) — this leaves that controller and its test not compiling
 * until then, in addition to the port-adapter red build already documented in {@code tasks.md}.
 */
@Service
public class TrustSnapshotService {

    private final TrustAnchorSyncService anchorService;
    private final TrustedEntityService entityService;
    private final SnapshotSignerPort signer;
    private final TrustRegistryProperties properties;
    private final Clock clock;
    private final SnapshotVersionResolver versionResolver;
    private final SnapshotPublicationObserverPort publicationObserver;

    public TrustSnapshotService(TrustAnchorSyncService anchorService,
                                TrustedEntityService entityService,
                                SnapshotSignerPort signer,
                                TrustRegistryProperties properties,
                                Clock clock,
                                PublishedSnapshotRepositoryPort publishedSnapshotRepository,
                                SnapshotPublicationObserverPort publicationObserver) {
        this.anchorService = anchorService;
        this.entityService = entityService;
        this.signer = signer;
        this.properties = properties;
        this.clock = clock;
        this.versionResolver = new SnapshotVersionResolver(publishedSnapshotRepository);
        this.publicationObserver = publicationObserver;
    }

    /**
     * Publishes the trust snapshot for {@code tenantId}: the previous publication unchanged if
     * neither source changed since (AC-05, EC-03), or a newly sealed and signed one otherwise.
     *
     * <p>Both sources are read exactly once each, up front (RC-2/RC-3): {@link
     * TrustAnchorSyncService#currentAnchorSet()} for the anchors — global, never scoped to a
     * tenant (AD-5) — and {@link TrustedEntityService#list(String)} for the tenant's private
     * entities, which already returns an empty list for a tenant with no entries (AC-07, EC-02,
     * ES-05) without needing a dedicated existence check. Neither source is filtered by validity
     * window before publishing (EC-05, AD-13 of the repository): the snapshot transports the
     * window, and usability is resolved by whoever evaluates it against an instant.
     *
     * <p>Building the candidate {@link TrustSnapshot} and signing it are deferred to {@link
     * SnapshotVersionResolver#resolve}, which only invokes them on the "content changed" path.
     * If either throws, the exception propagates out of this method before any write to {@link
     * PublishedSnapshotRepositoryPort} happens, so the published version cannot have advanced
     * (ES-03).
     */
    public PublishedSnapshot publishFor(String tenantId) {
        TrustAnchorSet anchorSet = anchorService.currentAnchorSet();
        List<TrustedEntity> entities = entityService.list(tenantId);

        Instant now = Instant.now(clock);
        boolean officialTrustStale = anchorSet.isStaleAt(now, properties.maxAge());
        Instant officialTrustLastSyncedAt = anchorSet.lastSuccessfulSyncAt();
        TrustProfile trustProfile = properties.trustProfile();

        SnapshotFingerprint fingerprint = SnapshotFingerprint.of(anchorSet, entities, trustProfile);

        PublishedSnapshot published = versionResolver.resolve(
                tenantId,
                fingerprint,
                version -> new TrustSnapshot(
                        tenantId,
                        version,
                        Instant.now(clock),
                        properties.snapshotTimeToLiveSeconds(),
                        anchorSet.anchors(),
                        entities,
                        trustProfile,
                        officialTrustStale,
                        officialTrustLastSyncedAt),
                signer::sign);

        publicationObserver.recordPublication(tenantId, published.version(), officialTrustStale);
        return published;
    }

    /** The compact JWS a consumer verifies offline — the signed document of {@link #publishFor}. */
    public String buildSigned(String tenantId) {
        return publishFor(tenantId).signedDocument();
    }
}
