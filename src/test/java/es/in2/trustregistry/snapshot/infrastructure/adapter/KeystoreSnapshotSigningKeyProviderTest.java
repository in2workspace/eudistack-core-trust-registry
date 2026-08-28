package es.in2.trustregistry.snapshot.infrastructure.adapter;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import es.in2.trustregistry.shared.infrastructure.config.TrustRegistryProperties;
import es.in2.trustregistry.snapshot.domain.model.TrustProfile;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-fast unit tests for {@link KeystoreSnapshotSigningKeyProvider} (ES-01,
 * NFR-S-228-01), written alongside the class rather than deferred to task 19.
 *
 * <p>Task 19 nominally owns "unit tests of {@code JwsSnapshotSigner} /
 * {@code KeystoreSnapshotSigningKeyProvider}" as a pair, but that pairing only makes sense
 * once {@code JwsSnapshotSigner} (task 12) actually consumes the bean this class produces —
 * task 19 is where the cross-class NFR-S-228-01 sweep and {@code JwsSnapshotSigner}'s own
 * tests belong. The fail-fast-at-construction behaviour tested here is entirely self-contained
 * to this class: it has no dependency on task 12, and this is the highest-risk new class in the
 * Story (custody of the production signing key, {@code AD-2}). Leaving it unverified until
 * task 19 would repeat the exact gap {@code R-1} already called out for the fingerprint
 * (task 16 folded into task 1) — the riskiest new code should not ship without its own test.
 * Same reasoning that led {@code DssTrustListJobConfigTest} (EUD-227) to test
 * {@code DssTrustListJobConfig} directly, in its own task, rather than waiting for a later
 * consumer.
 */
class KeystoreSnapshotSigningKeyProviderTest {

    private static final ResourceLoader RESOURCE_LOADER = new DefaultResourceLoader();
    private static final String VALID_KEYSTORE = "classpath:fixtures/snapshot/keystore/valid-signing-keystore.p12";
    private static final String RSA_KEYSTORE = "classpath:fixtures/snapshot/keystore/rsa-signing-keystore.p12";
    private static final String CORRUPT_KEYSTORE = "classpath:dss-config/invalid-keystore.p12";
    private static final String CORRECT_PASSWORD = "snapshot-test-password";
    private static final String CORRECT_ALIAS = "snapshot-signing";

    @Test
    void snapshotSigningKey_ValidKeystoreAndCredentials_LoadsThePrivateKey() {
        // Arrange
        KeystoreSnapshotSigningKeyProvider provider = providerFor(
                VALID_KEYSTORE, CORRECT_PASSWORD, CORRECT_ALIAS);

        // Act
        ECKey signingKey = provider.snapshotSigningKey();

        // Assert: this is what task 12's JwsSnapshotSigner will sign with — must be the
        // private half, on the curve ES256 requires.
        assertThat(signingKey.isPrivate()).isTrue();
        assertThat(signingKey.getCurve()).isEqualTo(Curve.P_256);
    }

    @Test
    void snapshotSigningKey_MissingKeystore_FailsFastAtConstruction() {
        // Arrange: ES-01 — an unresolvable keystore location must abort startup, never be
        // treated as "no signing key configured, skip signing".
        KeystoreSnapshotSigningKeyProvider provider = providerFor(
                "classpath:fixtures/snapshot/keystore/does-not-exist.p12", CORRECT_PASSWORD, CORRECT_ALIAS);

        // Act & Assert
        assertThatThrownBy(provider::snapshotSigningKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does-not-exist.p12");
    }

    @Test
    void snapshotSigningKey_UnreadableKeystore_FailsFastAtConstruction() {
        // Arrange: ES-01 — present but unparseable content must fail the same way as a
        // missing file.
        KeystoreSnapshotSigningKeyProvider provider = providerFor(
                CORRUPT_KEYSTORE, CORRECT_PASSWORD, CORRECT_ALIAS);

        // Act & Assert
        assertThatThrownBy(provider::snapshotSigningKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CORRUPT_KEYSTORE.substring(CORRUPT_KEYSTORE.lastIndexOf('/') + 1));
    }

    @Test
    void snapshotSigningKey_WrongPassword_FailsFastAtConstruction() {
        // Arrange: ES-01 — incorrect credentials must abort startup with an explicit reason,
        // never silently produce a null/garbage key. The configured password opens both the
        // keystore itself and the key entry under the alias, so a wrong password fails at the
        // keystore level first.
        KeystoreSnapshotSigningKeyProvider provider = providerFor(
                VALID_KEYSTORE, "definitely-not-the-password", CORRECT_ALIAS);

        // Act & Assert
        assertThatThrownBy(provider::snapshotSigningKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(VALID_KEYSTORE.substring(VALID_KEYSTORE.lastIndexOf('/') + 1));
    }

    @Test
    void snapshotSigningKey_UnknownAlias_FailsFastAtConstruction() {
        // Arrange: ES-01 — a well-formed keystore with no entry under the configured alias
        // must fail the same way as a missing keystore, not silently sign with nothing.
        KeystoreSnapshotSigningKeyProvider provider = providerFor(
                VALID_KEYSTORE, CORRECT_PASSWORD, "no-such-alias");

        // Act & Assert
        assertThatThrownBy(provider::snapshotSigningKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such-alias");
    }

    @Test
    void snapshotSigningKey_NonEcKeyEntry_FailsFastAtConstruction() {
        // Arrange: ES-01 — JwsSnapshotSigner (task 12) signs with ES256, which requires an
        // EC key; an RSA entry under the configured alias must not be handed out as if it
        // were usable.
        KeystoreSnapshotSigningKeyProvider provider = providerFor(
                RSA_KEYSTORE, CORRECT_PASSWORD, CORRECT_ALIAS);

        // Act & Assert
        assertThatThrownBy(provider::snapshotSigningKey)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CORRECT_ALIAS);
    }

    private static KeystoreSnapshotSigningKeyProvider providerFor(String path, String password, String alias) {
        TrustRegistryProperties properties = new TrustRegistryProperties(
                "https://ec.europa.eu/tools/lotl/eu-lotl.xml",
                "classpath:keystore/oj-keystore.p12",
                "build/test-cache",
                86_400L,
                Duration.ofHours(24),
                new TrustRegistryProperties.Sync(Duration.ofSeconds(10), Duration.ofHours(6)),
                new TrustRegistryProperties.Signing(path, password, alias),
                TrustProfile.PRODUCTION);
        return new KeystoreSnapshotSigningKeyProvider(properties, RESOURCE_LOADER);
    }
}
