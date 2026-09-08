package es.in2.trustregistry.shared.infrastructure.config;

import jakarta.validation.Valid;
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
 */
@ConfigurationProperties(prefix = "trust-registry")
@Validated
public record TrustRegistryProperties(
        String lotlUrl,
        String officialKeystorePath,
        String cacheDirectory,
        long snapshotTimeToLiveSeconds,
        @NotNull Duration maxAge,
        @NotNull @Valid Sync sync
) {

    /**
     * @param initialDelay delay before the first scheduled online refresh after startup (AD-2)
     * @param interval     cadence between scheduled online refreshes thereafter
     */
    @Validated
    public record Sync(@NotNull Duration initialDelay, @NotNull Duration interval) {
    }
}
