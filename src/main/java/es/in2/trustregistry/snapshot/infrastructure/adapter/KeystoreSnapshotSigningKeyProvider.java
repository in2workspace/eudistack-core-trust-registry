package es.in2.trustregistry.snapshot.infrastructure.adapter;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.ECKey;
import es.in2.trustregistry.shared.infrastructure.config.TrustRegistryProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * Loads and validates the snapshot signing key pair (ES-01, AD-2 of EUD-228) at context
 * refresh, never lazily. The signing key is a non-exportable secret injected from outside
 * the process — see {@link TrustRegistryProperties.Signing} — never generated at runtime;
 * this class replaces {@code JwsSnapshotSigner.generateEphemeralKey()} (task 12) as the
 * source of the key {@link es.in2.trustregistry.snapshot.domain.port.SnapshotSignerPort}
 * implementations sign with.
 *
 * <p>Same fail-fast-at-{@code @Configuration}-construction pattern as {@link
 * es.in2.trustregistry.anchors.infrastructure.adapter.dss.DssTrustListJobConfig#officialSigningCertificateSource()}
 * for the official OJ keystore: a missing, unreadable, or wrongly-credentialed keystore
 * aborts application startup with an {@link IllegalStateException} rather than surfacing as
 * a failure on the first publication request. A registry that started without being able to
 * sign would otherwise be able to publish an unverifiable snapshot, which is exactly what
 * this component exists to prevent.
 *
 * <p>The keystore entry MUST hold an EC key pair — {@link JwsSnapshotSigner} signs with
 * ES256, which requires curve P-256. {@link ECKey#load(KeyStore, String, char[])} already
 * rejects any other key algorithm (e.g. RSA) held under the configured alias with a {@link
 * JOSEException}, so no additional type check is needed here.
 *
 * <p>{@code NFR-S-228-01}: nothing in this class logs the loaded key, its private scalar, or
 * the keystore password — failure messages identify only the configured path and alias, both
 * non-secret configuration values, never key material.
 */
@Configuration
public class KeystoreSnapshotSigningKeyProvider {

    private static final String SIGNING_KEYSTORE_TYPE = "PKCS12";

    private final TrustRegistryProperties properties;
    private final ResourceLoader resourceLoader;

    public KeystoreSnapshotSigningKeyProvider(TrustRegistryProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * Loads and validates the snapshot signing key pair (ES-01). Failure here aborts context
     * refresh, so a bad keystore is a startup failure, never a signing-time surprise.
     */
    @Bean
    public ECKey snapshotSigningKey() {
        TrustRegistryProperties.Signing signing = properties.signing();
        ECKey signingKey = load(signing);
        if (signingKey == null) {
            throw new IllegalStateException(
                    "No key entry found under alias '" + signing.alias() + "' in the snapshot signing keystore '"
                            + signing.path() + "' (ES-01)");
        }
        if (!signingKey.isPrivate()) {
            throw new IllegalStateException(
                    "The snapshot signing keystore entry '" + signing.alias() + "' in '" + signing.path()
                            + "' has no private key; the registry cannot sign snapshots without it (ES-01)");
        }
        return signingKey;
    }

    /**
     * Isolates every checked and unchecked failure the underlying keystore/JOSE APIs can
     * raise into a single wrapping point, so {@link #snapshotSigningKey()} only ever has to
     * reason about "loaded" vs "did not load" (ES-01).
     */
    private ECKey load(TrustRegistryProperties.Signing signing) {
        Resource keystoreResource = resourceLoader.getResource(signing.path());
        try (InputStream keystoreStream = keystoreResource.getInputStream()) {
            KeyStore keyStore = KeyStore.getInstance(SIGNING_KEYSTORE_TYPE);
            keyStore.load(keystoreStream, signing.password().toCharArray());
            return ECKey.load(keyStore, signing.alias(), signing.password().toCharArray());
        } catch (IOException | GeneralSecurityException | JOSEException e) {
            throw new IllegalStateException(
                    "Cannot load the snapshot signing key from '" + signing.path() + "' under alias '"
                            + signing.alias() + "'; the registry cannot publish a verifiable snapshot without it "
                            + "(ES-01)", e);
        }
    }
}
