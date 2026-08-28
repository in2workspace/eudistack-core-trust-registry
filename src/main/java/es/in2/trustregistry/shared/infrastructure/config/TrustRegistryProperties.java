package es.in2.trustregistry.shared.infrastructure.config;

import es.in2.trustregistry.snapshot.domain.model.TrustProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuration of the registry.
 *
 * @param lotlUrl URL of the EU List of Trusted Lists (ETSI TS 119 612)
 * @param officialKeystorePath location of the OJ keystore holding the LOTL signing certificates
 * @param cacheDirectory directory where synchronised lists are cached for offline startup
 * @param snapshotTimeToLiveSeconds how long a published snapshot stays usable by a consumer
 * @param maxAge maximum age a successful synchronisation may reach before the anchor set is
 *               flagged as stale (AC-06); the set is never emptied for this alone (AD-3)
 * @param sync tuning of the background synchronisation job
 * @param signing location and credentials of the snapshot signing material (ES-01, AD-2 of
 *                EUD-228); loaded and validated eagerly by
 *                {@code KeystoreSnapshotSigningKeyProvider} (task 11), never lazily
 * @param trustProfile trust profile this deployment publishes snapshots under (AC-03); no
 *                      Java-side default is set here — a misconfigured deployment must fail
 *                      to bind rather than silently fall back, so the strict default
 *                      (PRODUCTION, AD-6 of EUD-228) is supplied by {@code application.yaml}
 *                      (task 15), not by this record
 */
@ConfigurationProperties(prefix = "trust-registry")
@Validated
public record TrustRegistryProperties(
        String lotlUrl,
        String officialKeystorePath,
        String cacheDirectory,
        long snapshotTimeToLiveSeconds,
        @NotNull Duration maxAge,
        @NotNull @Valid Sync sync,
        @NotNull @Valid Signing signing,
        @NotNull TrustProfile trustProfile
) {

    /**
     * @param initialDelay delay before the first scheduled online refresh after startup (AD-2)
     * @param interval     cadence between scheduled online refreshes thereafter
     */
    @Validated
    public record Sync(@NotNull Duration initialDelay, @NotNull Duration interval) {
    }

    /**
     * Snapshot signing material (ES-01, AD-2 of EUD-228): a PKCS#12 keystore holding the
     * non-exportable signing key, injected from outside the process — never generated at
     * runtime. Follows the same path-based convention as {@code officialKeystorePath}
     * (see {@code DssTrustListJobConfig}), so it accepts any Spring {@code Resource} location
     * (e.g. {@code classpath:} for local development, {@code file:} for a mounted secret).
     *
     * @param path     location of the PKCS#12 keystore holding the signing key pair
     * @param password keystore password
     * @param alias    alias of the signing key entry within the keystore
     */
    @Validated
    public record Signing(@NotBlank String path, @NotBlank String password, @NotBlank String alias) {
    }
}
