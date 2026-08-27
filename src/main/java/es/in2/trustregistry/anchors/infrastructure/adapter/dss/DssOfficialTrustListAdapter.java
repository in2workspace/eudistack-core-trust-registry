package es.in2.trustregistry.anchors.infrastructure.adapter.dss;

import es.in2.trustregistry.anchors.domain.model.ListRejection;
import es.in2.trustregistry.anchors.domain.model.ListRejection.RejectionReason;
import es.in2.trustregistry.anchors.domain.model.SyncOutcome;
import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustServiceStatus;
import es.in2.trustregistry.anchors.domain.port.OfficialTrustListPort;
import eu.europa.esig.dss.model.timedependent.TimeDependentValues;
import eu.europa.esig.dss.model.tsl.LOTLInfo;
import eu.europa.esig.dss.model.tsl.ParsingInfoRecord;
import eu.europa.esig.dss.model.tsl.TLInfo;
import eu.europa.esig.dss.model.tsl.TLValidationJobSummary;
import eu.europa.esig.dss.model.tsl.TrustService;
import eu.europa.esig.dss.model.tsl.TrustServiceProvider;
import eu.europa.esig.dss.model.tsl.TrustServiceStatusAndInformationExtensions;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.tsl.job.TLValidationJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Driven adapter over the European Commission DSS library (ETSI TS 119 612).
 *
 * <p>Delegates the entire list lifecycle to DSS's {@link TLValidationJob} ({@code AD-1}):
 * this class only triggers a refresh, reads back {@link TLValidationJobSummary} and
 * translates it to the domain. It never touches DSS's {@code TrustedListsCertificateSource}
 * directly — {@link DssTrustListJobConfig} wires that bean only so DSS itself can populate
 * and synchronise it; this adapter derives every {@link TrustAnchor} from the per-list
 * summary instead, which is what carries the download/parsing/validation outcome that
 * {@link SyncOutcome} needs.
 *
 * <p>Per {@code AD-4}/{@code AC-03}, mapping never filters by service status: every
 * {@link TrustServiceStatusAndInformationExtensions} time window found for every service
 * becomes one {@link TrustAnchor}, whatever its status, so that {@link TrustAnchor#isUsableAt}
 * can later answer for any instant, not just now.
 *
 * <p>DSS processes each list independently ({@code EC-01}, {@code AC-02}): a list whose
 * download failed or whose signature does not verify becomes one {@link ListRejection} and
 * is simply excluded from the anchors gathered, without aborting the run for the other
 * lists. A LOTL itself failing verification ({@code ES-02}) is reported the same way and, by
 * construction, carries no derived national lists to process.
 *
 * <p>Per {@code AD-2}, {@link #fetchAnchors()} ({@code onlineRefresh()}) and
 * {@link #fetchAnchorsFromCache()} ({@code offlineRefresh()}) are two distinct DSS
 * operations exposed as two methods on this class, not two port methods: which one runs is
 * an infrastructure/trigger concern ({@link TrustAnchorSyncScheduler} at startup vs on the
 * schedule), not a domain one — {@link OfficialTrustListPort} only ever promises "the current
 * sync outcome". Both share the mapping logic below, which reads {@code getSummary()} the
 * same way regardless of which refresh populated it.
 *
 * <p>DSS is blocking by design, which is why this service runs WebMvc on virtual threads
 * rather than WebFlux.
 */
@Slf4j
@Component
public class DssOfficialTrustListAdapter implements OfficialTrustListPort {

    private final TLValidationJob trustListValidationJob;

    public DssOfficialTrustListAdapter(TLValidationJob trustListValidationJob) {
        this.trustListValidationJob = trustListValidationJob;
    }

    @Override
    public SyncOutcome fetchAnchors() {
        trustListValidationJob.onlineRefresh();
        return buildOutcomeFromSummary();
    }

    /**
     * Populates the anchor set from the on-disk cache only, touching no network
     * ({@code AD-2}). Used at startup ({@code AC-05}) instead of {@link #fetchAnchors()},
     * so a cold or unreachable network never blocks the service from coming up.
     *
     * <p>DSS's {@code offlineRefresh()} runs the exact same download/parse/validate pipeline
     * as {@code onlineRefresh()}, only backed by the offline {@code FileCacheDataLoader}
     * ({@link DssTrustListJobConfig#offlineTrustListLoader()}). When a list has never been
     * cached, that loader reports a download error for it, same as an unreachable source
     * would ({@code EC-01}'s path); the mapping below is refresh-mode-agnostic and already
     * turns that into a {@link ListRejection} with no anchors derived, which is exactly what
     * {@code EC-04} requires for "no cache and no network": an empty, non-exceptional result.
     */
    public SyncOutcome fetchAnchorsFromCache() {
        trustListValidationJob.offlineRefresh();
        return buildOutcomeFromSummary();
    }

    private SyncOutcome buildOutcomeFromSummary() {
        TLValidationJobSummary summary = trustListValidationJob.getSummary();

        List<TrustAnchor> anchors = new ArrayList<>();
        List<ListRejection> rejections = new ArrayList<>();
        List<String> listsWithStaleNextUpdate = new ArrayList<>();

        for (LOTLInfo lotlInfo : summary.getLOTLInfos()) {
            processLotl(lotlInfo, anchors, rejections, listsWithStaleNextUpdate);
        }
        for (TLInfo tlInfo : summary.getOtherTLInfos()) {
            processTl(tlInfo, anchors, rejections, listsWithStaleNextUpdate);
        }

        return new SyncOutcome(anchors, rejections, listsWithStaleNextUpdate);
    }

    private void processLotl(LOTLInfo lotlInfo, List<TrustAnchor> anchors, List<ListRejection> rejections,
                              List<String> listsWithStaleNextUpdate) {
        String listIdentifier = lotlInfo.getUrl();

        if (lotlInfo.getDownloadCacheInfo().isError()) {
            rejections.add(new ListRejection(listIdentifier, RejectionReason.UNREACHABLE,
                    lotlInfo.getDownloadCacheInfo().getExceptionMessage()));
            log.warn("LOTL '{}' is unreachable; keeping the last known good content (EC-01)", listIdentifier);
            return;
        }
        if (lotlInfo.getValidationCacheInfo().isInvalid()) {
            rejections.add(new ListRejection(listIdentifier, RejectionReason.SIGNATURE_INVALID,
                    "LOTL signature does not verify against the official signing certificates"));
            log.warn("LOTL '{}' failed signature verification; no national list derived from it "
                    + "this run (ES-02)", listIdentifier);
            return;
        }

        if (isNextUpdatePassed(listIdentifier, lotlInfo.getParsingCacheInfo())) {
            listsWithStaleNextUpdate.add(listIdentifier);
        }

        for (TLInfo tlInfo : lotlInfo.getTLInfos()) {
            processTl(tlInfo, anchors, rejections, listsWithStaleNextUpdate);
        }
    }

    private void processTl(TLInfo tlInfo, List<TrustAnchor> anchors, List<ListRejection> rejections,
                            List<String> listsWithStaleNextUpdate) {
        String listIdentifier = tlInfo.getUrl();

        if (tlInfo.getDownloadCacheInfo().isError()) {
            rejections.add(new ListRejection(listIdentifier, RejectionReason.UNREACHABLE,
                    tlInfo.getDownloadCacheInfo().getExceptionMessage()));
            log.warn("Trusted list '{}' is unreachable; keeping the last known good content (EC-01)",
                    listIdentifier);
            return;
        }
        if (tlInfo.getValidationCacheInfo().isInvalid()) {
            rejections.add(new ListRejection(listIdentifier, RejectionReason.SIGNATURE_INVALID,
                    "Trusted list signature does not verify against the official signing certificates"));
            log.warn("Trusted list '{}' failed signature verification; discarding its content "
                    + "for this run (AC-02)", listIdentifier);
            return;
        }

        if (isNextUpdatePassed(listIdentifier, tlInfo.getParsingCacheInfo())) {
            listsWithStaleNextUpdate.add(listIdentifier);
        }

        ParsingInfoRecord parsingInfo = tlInfo.getParsingCacheInfo();
        String territory = parsingInfo.getTerritory();
        for (TrustServiceProvider provider : parsingInfo.getTrustServiceProviders()) {
            for (TrustService service : provider.getServices()) {
                anchors.addAll(toAnchors(service, territory));
            }
        }
    }

    private List<TrustAnchor> toAnchors(TrustService service, String territory) {
        List<TrustAnchor> serviceAnchors = new ArrayList<>();
        TimeDependentValues<TrustServiceStatusAndInformationExtensions> statusHistory =
                service.getStatusAndInformationExtensions();
        for (CertificateToken certificate : service.getCertificates()) {
            String subject = certificate.getSubject().getRFC2253();
            String certificatePem = DSSUtils.convertToPEM(certificate);
            for (TrustServiceStatusAndInformationExtensions statusPeriod : statusHistory) {
                serviceAnchors.add(new TrustAnchor(
                        subject,
                        certificatePem,
                        territory,
                        statusPeriod.getType(),
                        toDomainStatus(statusPeriod.getStatus()),
                        toInstant(statusPeriod.getStartDate()),
                        toInstant(statusPeriod.getEndDate())));
            }
        }
        return serviceAnchors;
    }

    /**
     * ETSI TS 119 612 defines only {@code granted} and {@code withdrawn} as post-eIDAS service
     * statuses (plus a handful of pre-eIDAS legacy statuses, none of which mean "granted"
     * either). Anything other than {@code granted} maps to {@link TrustServiceStatus#WITHDRAWN}
     * so it is preserved — never dropped — while still being correctly excluded by
     * {@link TrustAnchor#isUsableAt}; an unrecognised or missing status URI maps to
     * {@link TrustServiceStatus#UNKNOWN}.
     */
    private TrustServiceStatus toDomainStatus(String statusUri) {
        if (statusUri == null) {
            return TrustServiceStatus.UNKNOWN;
        }
        if (statusUri.endsWith("/Svcstatus/granted")) {
            return TrustServiceStatus.GRANTED;
        }
        if (statusUri.endsWith("/Svcstatus/withdrawn")) {
            return TrustServiceStatus.WITHDRAWN;
        }
        return TrustServiceStatus.UNKNOWN;
    }

    private Instant toInstant(java.util.Date date) {
        return date == null ? null : date.toInstant();
    }

    /**
     * EC-02: a next-update date already in the past does not reject the list — its content is
     * still accepted as long as the signature verifies — but the condition must be visible to
     * the operation. Logged for immediate diagnosis and, per {@code NFR-O-227-01}, surfaced to
     * the caller so it can become a first-class {@link SyncOutcome#listsWithStaleNextUpdate()}
     * entry instead of only a log line (see the tech-debt note filed alongside task 8, resolved
     * by task 12).
     */
    private boolean isNextUpdatePassed(String listIdentifier, ParsingInfoRecord parsingInfo) {
        java.util.Date nextUpdateDate = parsingInfo.getNextUpdateDate();
        boolean stale = nextUpdateDate != null && nextUpdateDate.toInstant().isBefore(Instant.now());
        if (stale) {
            log.warn("List '{}' declares a next-update date ({}) already in the past; its "
                    + "content is still accepted because its signature verifies (EC-02)",
                    listIdentifier, nextUpdateDate.toInstant());
        }
        return stale;
    }
}
