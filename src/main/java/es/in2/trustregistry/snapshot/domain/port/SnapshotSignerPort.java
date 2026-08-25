package es.in2.trustregistry.snapshot.domain.port;

import es.in2.trustregistry.snapshot.domain.model.TrustSnapshot;
import reactor.core.publisher.Mono;

/**
 * Driven port: turns a snapshot into a detached, verifiable artefact.
 * Implementations MUST produce something a consumer can verify offline.
 */
public interface SnapshotSignerPort {

    /** Returns the snapshot serialised as a signed JWS in compact form. */
    Mono<String> sign(TrustSnapshot snapshot);
}
