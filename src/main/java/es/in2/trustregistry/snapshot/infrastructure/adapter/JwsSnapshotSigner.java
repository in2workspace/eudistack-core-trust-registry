package es.in2.trustregistry.snapshot.infrastructure.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import es.in2.trustregistry.snapshot.domain.model.PublicVerificationKey;
import es.in2.trustregistry.snapshot.domain.model.TrustSnapshot;
import es.in2.trustregistry.snapshot.domain.port.SnapshotSignerPort;
import es.in2.trustregistry.snapshot.domain.port.SnapshotVerificationMaterialPort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Signs the snapshot with an ES256 key so consumers can verify it offline (AC-02).
 *
 * <p>The signing key is the material {@link KeystoreSnapshotSigningKeyProvider} loaded and
 * validated at startup (ES-01, AD-2 of EUD-228) — never generated at runtime. {@code kid} in
 * both the JWS header and the published verification material is the keystore alias the key
 * was loaded under, since {@link ECKey#load(java.security.KeyStore, String, char[])} already
 * sets it from the alias; no separate key identifier is assigned here.
 */
@Component
public class JwsSnapshotSigner implements SnapshotSignerPort, SnapshotVerificationMaterialPort {

    private final ObjectMapper objectMapper;
    private final ECKey signingKey;

    public JwsSnapshotSigner(ObjectMapper objectMapper, ECKey signingKey) {
        this.objectMapper = objectMapper;
        this.signingKey = signingKey;
    }

    /** Public part of the signing key, to be exposed as a JWKS by the consumer-facing API. */
    public ECKey publicKey() {
        return signingKey.toPublicJWK();
    }

    @Override
    public String sign(TrustSnapshot snapshot) {
        try {
            JWSSigner signer = new ECDSASigner(signingKey);
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.ES256)
                    .type(new JOSEObjectType("trust-snapshot+jwt"))
                    .keyID(signingKey.getKeyID())
                    .build();
            JWSObject jws = new JWSObject(header, new Payload(objectMapper.writeValueAsString(snapshot)));
            jws.sign(signer);
            return jws.serialize();
        } catch (JOSEException | JsonProcessingException error) {
            throw new IllegalStateException("Unable to sign the trust snapshot", error);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Derives the returned key exclusively from {@link ECKey#toPublicJWK()}, which strips
     * the private scalar; {@link PublicVerificationKey} has no field where it could be carried
     * even by mistake (NFR-S-228-01).
     */
    @Override
    public List<PublicVerificationKey> verificationMaterial() {
        ECKey publicKey = signingKey.toPublicJWK();
        return List.of(new PublicVerificationKey(
                publicKey.getKeyID(),
                publicKey.getCurve().getName(),
                publicKey.getX().toString(),
                publicKey.getY().toString()));
    }
}
