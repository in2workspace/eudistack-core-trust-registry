package es.in2.trustregistry.snapshot.infrastructure.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import es.in2.trustregistry.snapshot.domain.model.TrustSnapshot;
import es.in2.trustregistry.snapshot.domain.port.SnapshotSignerPort;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Signs the snapshot with an ES256 key so consumers can verify it offline.
 *
 * <p>Scaffolding: the key pair is generated in memory at startup, which means a restart
 * invalidates previously published snapshots. Production key custody (KMS, one key per
 * deployment, published JWKS) is delivered by US-02 of EUD-34.
 */
@Slf4j
@Component
public class JwsSnapshotSigner implements SnapshotSignerPort {

    private final ObjectMapper objectMapper;
    private ECKey signingKey;

    public JwsSnapshotSigner(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void generateEphemeralKey() throws Exception {
        this.signingKey = new ECKeyGenerator(Curve.P_256).keyID("trust-registry-dev").generate();
        log.warn("Using an ephemeral in-memory signing key; snapshots do not survive a restart");
    }

    /** Public part of the signing key, to be exposed as a JWKS by the consumer-facing API. */
    public ECKey publicKey() {
        return signingKey.toPublicJWK();
    }

    @Override
    public Mono<String> sign(TrustSnapshot snapshot) {
        return Mono.fromCallable(() -> {
            JWSSigner signer = new ECDSASigner(signingKey);
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                    .type(new JOSEObjectType("trust-snapshot+jwt"))
                    .keyID(signingKey.getKeyID())
                    .build();
            JWSObject jws = new JWSObject(header, new Payload(objectMapper.writeValueAsString(snapshot)));
            jws.sign(signer);
            return jws.serialize();
        });
    }
}
