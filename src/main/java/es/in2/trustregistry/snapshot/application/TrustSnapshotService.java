package es.in2.trustregistry.snapshot.application;

import es.in2.trustregistry.anchors.application.TrustAnchorSyncService;
import es.in2.trustregistry.entities.application.TrustedEntityService;
import es.in2.trustregistry.shared.infrastructure.config.TrustRegistryProperties;
import es.in2.trustregistry.snapshot.domain.model.TrustSnapshot;
import es.in2.trustregistry.snapshot.domain.port.SnapshotSignerPort;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Builds and signs the trust snapshot handed to consumers. */
@Service
public class TrustSnapshotService {

    private final TrustAnchorSyncService anchorService;
    private final TrustedEntityService entityService;
    private final SnapshotSignerPort signer;
    private final TrustRegistryProperties properties;
    private final Clock clock;
    private final AtomicLong version = new AtomicLong();

    public TrustSnapshotService(TrustAnchorSyncService anchorService,
                                TrustedEntityService entityService,
                                SnapshotSignerPort signer,
                                TrustRegistryProperties properties,
                                Clock clock) {
        this.anchorService = anchorService;
        this.entityService = entityService;
        this.signer = signer;
        this.properties = properties;
        this.clock = clock;
    }

    public TrustSnapshot build(String tenantId) {
        return new TrustSnapshot(
                tenantId,
                version.incrementAndGet(),
                Instant.now(clock),
                properties.snapshotTimeToLiveSeconds(),
                anchorService.currentAnchors(),
                entityService.list(tenantId));
    }

    public String buildSigned(String tenantId) {
        return signer.sign(build(tenantId));
    }
}
