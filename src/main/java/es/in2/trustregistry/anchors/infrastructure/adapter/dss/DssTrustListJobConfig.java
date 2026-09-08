package es.in2.trustregistry.anchors.infrastructure.adapter.dss;

import es.in2.trustregistry.shared.infrastructure.config.TrustRegistryProperties;
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader;
import eu.europa.esig.dss.service.http.commons.FileCacheDataLoader;
import eu.europa.esig.dss.spi.client.http.IgnoreDataLoader;
import eu.europa.esig.dss.spi.tsl.TrustedListsCertificateSource;
import eu.europa.esig.dss.spi.x509.KeyStoreCertificateSource;
import eu.europa.esig.dss.tsl.cache.CacheCleaner;
import eu.europa.esig.dss.tsl.job.TLValidationJob;
import eu.europa.esig.dss.tsl.source.LOTLSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Wires the DSS context that {@link DssOfficialTrustListAdapter} (US-01, task 8) drives:
 * the EU LOTL source with pivot support, a file-backed offline cache and the trusted-list
 * certificate source that collects the outcome of a run.
 *
 * <p>Per {@code AD-1} of the Story tech-design, list lifecycle (download, pivot chaining,
 * signature verification, offline cache) is entirely delegated to DSS's own
 * {@link TLValidationJob}; this class only assembles it from
 * {@link TrustRegistryProperties} and translates nothing itself.
 *
 * <p>The official signing-certificate keystore is loaded eagerly, while this
 * {@code @Configuration} class is processed during context refresh — not lazily on the
 * first sync — so a missing or unreadable keystore fails application startup
 * ({@code ES-01}). {@link KeyStoreCertificateSource}'s constructor reads and parses the
 * keystore immediately; there is no deferred/lazy initialisation to opt out of here, so no
 * separate readiness check is needed.
 *
 * <p>The bundled keystore ({@code classpath:keystore/oj-keystore.p12}) is DSS's own
 * reference OJ keystore, vendored unmodified from {@code dss-cookbook} 6.4
 * ({@code oj_2019/ec.europa.eu.1-8.cer}, password {@code dss-password}): per {@code AD-1},
 * this repository delegates the full list lifecycle to DSS, and that delegation extends to
 * the trust anchor DSS itself validates against in its own tests. Most of the eight
 * certificates in that bundle are already expired (only one remains valid, through 2028) —
 * this is a known upstream staleness issue in DSS's cookbook module, not something patched
 * locally; a real OJ certificate set diverging from what DSS ships and tests against would
 * make this repository's trust root untested by DSS's own suite. See
 * {@code docs/architecture.md} for the tracking note and the upstream issue filed against
 * {@code esig/dss}.
 */
@Configuration
public class DssTrustListJobConfig {

    private static final String LOTL_KEYSTORE_TYPE = "PKCS12";
    private static final char[] LOTL_KEYSTORE_PASSWORD = "dss-password".toCharArray();
    private static final List<Integer> SUPPORTED_TL_VERSIONS = List.of(5, 6);

    private final TrustRegistryProperties properties;
    private final ResourceLoader resourceLoader;

    public DssTrustListJobConfig(TrustRegistryProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * Loads and validates the official signing-certificate store (ES-01). Failure here
     * aborts context refresh, so a bad keystore is a startup failure, never a sync-time
     * surprise.
     */
    @Bean
    public KeyStoreCertificateSource officialSigningCertificateSource() {
        Resource keystoreResource = resourceLoader.getResource(properties.officialKeystorePath());
        try (InputStream keystoreStream = keystoreResource.getInputStream()) {
            return new KeyStoreCertificateSource(keystoreStream, LOTL_KEYSTORE_TYPE, LOTL_KEYSTORE_PASSWORD);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException(
                    "Cannot load the official signing-certificate keystore from '"
                            + properties.officialKeystorePath()
                            + "'; the registry cannot verify any trusted list without it (ES-01)", e);
        }
    }

    @Bean
    public LOTLSource europeanLotlSource(KeyStoreCertificateSource officialSigningCertificateSource) {
        LOTLSource lotlSource = new LOTLSource();
        lotlSource.setUrl(properties.lotlUrl());
        lotlSource.setCertificateSource(officialSigningCertificateSource);
        lotlSource.setPivotSupport(true);
        lotlSource.setTLVersions(SUPPORTED_TL_VERSIONS);
        return lotlSource;
    }

    /**
     * Populated by {@link TLValidationJob} runs and read by
     * {@link DssOfficialTrustListAdapter} (task 8) to derive {@code TrustAnchor}s.
     */
    @Bean
    public TrustedListsCertificateSource trustedListsCertificateSource() {
        return new TrustedListsCertificateSource();
    }

    private File cacheDirectory() {
        File directory = new File(properties.cacheDirectory());
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw new IllegalStateException(
                    "Cannot create the trusted list cache directory '" + properties.cacheDirectory() + "'");
        }
        return directory;
    }

    /**
     * Populates the cache from disk only, touching no network — what makes AC-05 possible.
     */
    @Bean
    public FileCacheDataLoader offlineTrustListLoader() {
        FileCacheDataLoader offlineLoader = new FileCacheDataLoader();
        offlineLoader.setCacheExpirationTime(-1);
        offlineLoader.setDataLoader(new IgnoreDataLoader());
        offlineLoader.setFileCacheDirectory(cacheDirectory());
        return offlineLoader;
    }

    /**
     * Always re-fetches from the official origin and refreshes the file cache — used by the
     * scheduled online refresh (AD-2), never at startup.
     */
    @Bean
    public FileCacheDataLoader onlineTrustListLoader() {
        FileCacheDataLoader onlineLoader = new FileCacheDataLoader();
        onlineLoader.setCacheExpirationTime(0);
        onlineLoader.setDataLoader(new CommonsDataLoader());
        onlineLoader.setFileCacheDirectory(cacheDirectory());
        return onlineLoader;
    }

    @Bean
    public CacheCleaner trustListCacheCleaner(FileCacheDataLoader onlineTrustListLoader) {
        CacheCleaner cacheCleaner = new CacheCleaner();
        cacheCleaner.setCleanMemory(true);
        cacheCleaner.setCleanFileSystem(true);
        cacheCleaner.setDSSFileLoader(onlineTrustListLoader);
        return cacheCleaner;
    }

    /**
     * Not run by this class: {@link DssOfficialTrustListAdapter} (task 8) calls
     * {@code offlineRefresh()} at startup and {@code onlineRefresh()} on the schedule
     * (AD-2); this bean only assembles the job.
     */
    @Bean
    public TLValidationJob trustListValidationJob(
            LOTLSource europeanLotlSource,
            TrustedListsCertificateSource trustedListsCertificateSource,
            FileCacheDataLoader offlineTrustListLoader,
            FileCacheDataLoader onlineTrustListLoader,
            CacheCleaner trustListCacheCleaner) {
        TLValidationJob job = new TLValidationJob();
        job.setListOfTrustedListSources(europeanLotlSource);
        job.setTrustedListCertificateSource(trustedListsCertificateSource);
        job.setOfflineDataLoader(offlineTrustListLoader);
        job.setOnlineDataLoader(onlineTrustListLoader);
        job.setCacheCleaner(trustListCacheCleaner);
        return job;
    }
}
