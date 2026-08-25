package es.in2.trustregistry.shared.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration of the registry.
 *
 * @param lotlUrl URL of the EU List of Trusted Lists (ETSI TS 119 612)
 * @param officialKeystorePath location of the OJ keystore holding the LOTL signing certificates
 * @param cacheDirectory directory where synchronised lists are cached for offline startup
 * @param snapshotTimeToLiveSeconds how long a published snapshot stays usable by a consumer
 */
@ConfigurationProperties(prefix = "trust-registry")
public record TrustRegistryProperties(
        String lotlUrl,
        String officialKeystorePath,
        String cacheDirectory,
        long snapshotTimeToLiveSeconds
) {
}
