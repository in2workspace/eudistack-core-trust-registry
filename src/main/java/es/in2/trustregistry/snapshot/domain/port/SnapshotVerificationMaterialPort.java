package es.in2.trustregistry.snapshot.domain.port;

import es.in2.trustregistry.snapshot.domain.model.PublicVerificationKey;

import java.util.List;

/**
 * Driven port: publishes the material a consumer needs to verify a signed snapshot offline
 * (AC-02, FR-17), and nothing else.
 *
 * <p>This port exists so the controller depends on a contract shaped for exactly one purpose —
 * handing out public verification material — instead of reaching into whatever signing adapter
 * happens to implement {@link SnapshotSignerPort} today, which is how the current scaffolding
 * exposes a key (the controller injects {@code JwsSnapshotSigner} directly and calls its
 * {@code publicKey()} method). That shortcut works only because the scaffolding's signer and its
 * key provider are the same object; it stops working the moment key custody moves to its own
 * component (task 11), and it gives the controller no reason not to reach for a method that
 * returns something wider than a public key.
 *
 * <p>{@code NFR-S-228-01} requires zero code paths that export or log private signing material.
 * This port is where that guarantee is anchored on the domain side: its return type, {@link
 * PublicVerificationKey}, has no field a private scalar could occupy, so no implementation of
 * this port — however careless — can return private material through it even by mistake.
 * Implementations MUST derive every key they return from the public half of the signing key only
 * (for example, the public-JWK projection of an EC key pair), and MUST NOT construct a {@link
 * PublicVerificationKey} from, or otherwise expose, the private key.
 */
public interface SnapshotVerificationMaterialPort {

    /**
     * The public verification keys a consumer needs to verify a snapshot signed by this
     * registry. Today this registry ever signs with a single key, so the list has exactly one
     * element; the return type is a list — the same shape a JWKS has — so a future key rotation
     * that briefly publishes an outgoing and an incoming key does not require widening this
     * contract.
     */
    List<PublicVerificationKey> verificationMaterial();
}
