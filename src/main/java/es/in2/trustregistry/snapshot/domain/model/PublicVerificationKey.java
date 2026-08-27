package es.in2.trustregistry.snapshot.domain.model;

import java.util.Objects;

/**
 * The public half of an EC key a consumer needs to verify a signed snapshot offline (AC-02,
 * NFR-S-228-01).
 *
 * <p>This record is deliberately shaped so it is structurally incapable of carrying private key
 * material: an EC JWK's private scalar (conventionally {@code d}) has no field to occupy here.
 * That is the guarantee behind {@code SnapshotVerificationMaterialPort} — not a comment asking
 * implementers to remember to omit it, but a type an adapter cannot misuse even carelessly. An
 * adapter that only ever has a private key in hand (for example, right after loading it from a
 * keystore) has no way to construct one of these without first deriving the public point from
 * it; there is nowhere to put the private scalar even by mistake.
 *
 * <p>Fields mirror the subset of RFC 7517 (JWK) that an EC public key needs: {@code curve} is
 * the JWK {@code crv} member (only {@code "P-256"} is produced by this registry today, matching
 * the ES256 algorithm {@code SnapshotSignerPort} implementations sign with), and {@code x}/{@code
 * y} are the base64url-encoded coordinates of the public point. This record carries no {@code
 * kty}/{@code use}/{@code alg} members: those are constant across every key this registry ever
 * publishes (EC, verification, ES256), so an adapter mapping this into a wire-format JWKS can
 * supply them as fixed values rather than this domain type transporting redundant constants.
 *
 * @param keyId key identifier ({@code kid}) a consumer uses to pick the right key once more than
 *              one has ever been published (for example, across a key rotation)
 * @param curve JWK {@code crv} member identifying the elliptic curve, e.g. {@code "P-256"}
 * @param x     base64url-encoded X coordinate of the public point
 * @param y     base64url-encoded Y coordinate of the public point
 */
public record PublicVerificationKey(String keyId, String curve, String x, String y) {

    public PublicVerificationKey {
        requireNonBlank(keyId, "keyId");
        requireNonBlank(curve, "curve");
        requireNonBlank(x, "x");
        requireNonBlank(y, "y");
    }

    private static void requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
