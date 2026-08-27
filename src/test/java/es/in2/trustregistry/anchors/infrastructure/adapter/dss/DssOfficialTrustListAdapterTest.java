package es.in2.trustregistry.anchors.infrastructure.adapter.dss;

import es.in2.trustregistry.anchors.domain.model.ListRejection.RejectionReason;
import es.in2.trustregistry.anchors.domain.model.SyncOutcome;
import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustServiceStatus;
import eu.europa.esig.dss.model.timedependent.TimeDependentValues;
import eu.europa.esig.dss.model.tsl.DownloadInfoRecord;
import eu.europa.esig.dss.model.tsl.LOTLInfo;
import eu.europa.esig.dss.model.tsl.ParsingInfoRecord;
import eu.europa.esig.dss.model.tsl.TLInfo;
import eu.europa.esig.dss.model.tsl.TLValidationJobSummary;
import eu.europa.esig.dss.model.tsl.TrustService;
import eu.europa.esig.dss.model.tsl.TrustServiceProvider;
import eu.europa.esig.dss.model.tsl.TrustServiceStatusAndInformationExtensions;
import eu.europa.esig.dss.model.tsl.ValidationInfoRecord;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.model.x509.X500PrincipalHelper;
import eu.europa.esig.dss.spi.DSSUtils;
import eu.europa.esig.dss.tsl.job.TLValidationJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DssOfficialTrustListAdapterTest {

    private static final String GRANTED_STATUS = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/granted";
    private static final String WITHDRAWN_STATUS = "http://uri.etsi.org/TrstSvc/TrustedList/Svcstatus/withdrawn";
    private static final String SERVICE_TYPE = "http://uri.etsi.org/TrstSvc/Svctype/CA/QC";

    @Mock
    private TLValidationJob trustListValidationJob;

    @Mock
    private TLValidationJobSummary summary;

    private DssOfficialTrustListAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new DssOfficialTrustListAdapter(trustListValidationJob);
    }

    @Test
    void fetchAnchors_ValidLotlAndNationalLists_ReturnsAnchorsPreservingStatusAndWindow() {
        // Arrange
        LOTLInfo lotlInfo = validLotlInfo("https://ec.europa.eu/lotl");

        TLInfo tlInfo = validTlInfo("https://tl.es/tl.xml");
        ParsingInfoRecord parsingInfo = validParsingInfo(tlInfo, "ES", List.of(provider(
                "ES",
                trustService(GRANTED_STATUS, Instant.parse("2026-01-01T00:00:00Z"), null))));
        when(lotlInfo.getTLInfos()).thenReturn(List.of(tlInfo));
        when(summary.getLOTLInfos()).thenReturn(List.of(lotlInfo));
        when(summary.getOtherTLInfos()).thenReturn(List.of());
        when(trustListValidationJob.getSummary()).thenReturn(summary);

        try (MockedStatic<DSSUtils> dssUtils = mockStatic(DSSUtils.class)) {
            dssUtils.when(() -> DSSUtils.convertToPEM(any())).thenReturn("pem");

            // Act
            SyncOutcome outcome = adapter.fetchAnchors();

            // Assert
            verify(trustListValidationJob).onlineRefresh();
            assertThat(outcome.rejections()).isEmpty();
            assertThat(outcome.anchors()).hasSize(1);
            TrustAnchor anchor = outcome.anchors().get(0);
            assertThat(anchor.territory()).isEqualTo("ES");
            assertThat(anchor.serviceType()).isEqualTo(SERVICE_TYPE);
            assertThat(anchor.status()).isEqualTo(TrustServiceStatus.GRANTED);
            assertThat(anchor.statusStartingTime()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
            assertThat(anchor.statusValidUntil()).isNull();
        }
        assertThat(parsingInfo).isNotNull();
    }

    @Test
    void fetchAnchors_NationalListSignatureInvalid_RejectsThatListOnlyAndKeepsOthers() {
        // Arrange: two national lists, one tampered, one valid — AC-02/EC-01 require the
        // tampered one to be discarded without aborting the run for the other.
        LOTLInfo lotlInfo = validLotlInfo("https://ec.europa.eu/lotl");

        TLInfo invalidTl = mockTlInfo("https://tl.tampered/tl.xml");
        DownloadInfoRecord invalidDownload = mockDownloadInfo(false, null);
        ValidationInfoRecord invalidValidation = mockValidationInfo(true);
        when(invalidTl.getDownloadCacheInfo()).thenReturn(invalidDownload);
        when(invalidTl.getValidationCacheInfo()).thenReturn(invalidValidation);

        TLInfo validTl = validTlInfo("https://tl.valid/tl.xml");
        validParsingInfo(validTl, "FR", List.of(provider(
                "FR",
                trustService(GRANTED_STATUS, Instant.parse("2026-01-01T00:00:00Z"), null))));

        when(lotlInfo.getTLInfos()).thenReturn(List.of(invalidTl, validTl));
        when(summary.getLOTLInfos()).thenReturn(List.of(lotlInfo));
        when(summary.getOtherTLInfos()).thenReturn(List.of());
        when(trustListValidationJob.getSummary()).thenReturn(summary);

        try (MockedStatic<DSSUtils> dssUtils = mockStatic(DSSUtils.class)) {
            dssUtils.when(() -> DSSUtils.convertToPEM(any())).thenReturn("pem");

            // Act
            SyncOutcome outcome = adapter.fetchAnchors();

            // Assert
            assertThat(outcome.rejections())
                    .extracting(r -> r.listIdentifier(), r -> r.reason())
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(
                            "https://tl.tampered/tl.xml", RejectionReason.SIGNATURE_INVALID));
            assertThat(outcome.anchors()).hasSize(1);
            assertThat(outcome.anchors().get(0).territory()).isEqualTo("FR");
        }
    }

    @Test
    void fetchAnchors_LotlSignatureInvalid_RejectsLotlAndDerivesNoNationalLists() {
        // Arrange: ES-02 — a LOTL that fails verification must not let any of its
        // (potentially stale) child TLInfos through.
        LOTLInfo lotlInfo = mockLotlInfo("https://ec.europa.eu/lotl");
        DownloadInfoRecord download = mockDownloadInfo(false, null);
        ValidationInfoRecord validation = mockValidationInfo(true);
        when(lotlInfo.getDownloadCacheInfo()).thenReturn(download);
        when(lotlInfo.getValidationCacheInfo()).thenReturn(validation);

        when(summary.getLOTLInfos()).thenReturn(List.of(lotlInfo));
        when(summary.getOtherTLInfos()).thenReturn(List.of());
        when(trustListValidationJob.getSummary()).thenReturn(summary);

        // Act
        SyncOutcome outcome = adapter.fetchAnchors();

        // Assert
        assertThat(outcome.anchors()).isEmpty();
        assertThat(outcome.rejections())
                .extracting(r -> r.listIdentifier(), r -> r.reason())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "https://ec.europa.eu/lotl", RejectionReason.SIGNATURE_INVALID));
    }

    @Test
    void fetchAnchors_NationalListUnreachable_RejectsAsUnreachableNotAsInvalidSignature() {
        // Arrange: EC-01 — a source that does not respond is a distinct rejection reason from
        // a tampered signature.
        LOTLInfo lotlInfo = validLotlInfo("https://ec.europa.eu/lotl");

        TLInfo unreachableTl = mockTlInfo("https://tl.unreachable/tl.xml");
        DownloadInfoRecord download = mockDownloadInfo(true, "connection timed out");
        when(unreachableTl.getDownloadCacheInfo()).thenReturn(download);

        when(lotlInfo.getTLInfos()).thenReturn(List.of(unreachableTl));
        when(summary.getLOTLInfos()).thenReturn(List.of(lotlInfo));
        when(summary.getOtherTLInfos()).thenReturn(List.of());
        when(trustListValidationJob.getSummary()).thenReturn(summary);

        // Act
        SyncOutcome outcome = adapter.fetchAnchors();

        // Assert
        assertThat(outcome.anchors()).isEmpty();
        assertThat(outcome.rejections())
                .extracting(r -> r.listIdentifier(), r -> r.reason(), r -> r.detail())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "https://tl.unreachable/tl.xml", RejectionReason.UNREACHABLE, "connection timed out"));
    }

    @Test
    void fetchAnchorsFromCache_ValidCachedLists_UsesOfflineRefreshInsteadOfOnline() {
        // Arrange: AC-05/EC-04 — the startup path must populate from the on-disk cache only,
        // never touching the network.
        LOTLInfo lotlInfo = validLotlInfo("https://ec.europa.eu/lotl");
        TLInfo tlInfo = validTlInfo("https://tl.es/tl.xml");
        validParsingInfo(tlInfo, "ES", List.of(provider(
                "ES",
                trustService(GRANTED_STATUS, Instant.parse("2026-01-01T00:00:00Z"), null))));
        when(lotlInfo.getTLInfos()).thenReturn(List.of(tlInfo));
        when(summary.getLOTLInfos()).thenReturn(List.of(lotlInfo));
        when(summary.getOtherTLInfos()).thenReturn(List.of());
        when(trustListValidationJob.getSummary()).thenReturn(summary);

        try (MockedStatic<DSSUtils> dssUtils = mockStatic(DSSUtils.class)) {
            dssUtils.when(() -> DSSUtils.convertToPEM(any())).thenReturn("pem");

            // Act
            SyncOutcome outcome = adapter.fetchAnchorsFromCache();

            // Assert
            verify(trustListValidationJob).offlineRefresh();
            verify(trustListValidationJob, org.mockito.Mockito.never()).onlineRefresh();
            assertThat(outcome.anchors()).hasSize(1);
            assertThat(outcome.rejections()).isEmpty();
        }
    }

    @Test
    void fetchAnchorsFromCache_NoCacheEntryForList_ReportsUnreachableAndReturnsEmptyAnchors() {
        // Arrange: EC-04 — no prior cache and no network must resolve to an empty, non-
        // exceptional outcome, exactly like an unreachable source (EC-01), not an error.
        LOTLInfo lotlInfo = validLotlInfo("https://ec.europa.eu/lotl");

        TLInfo neverCachedTl = mockTlInfo("https://tl.es/tl.xml");
        DownloadInfoRecord neverCachedDownload = mockDownloadInfo(true, "no cached entry available");
        when(neverCachedTl.getDownloadCacheInfo()).thenReturn(neverCachedDownload);

        when(lotlInfo.getTLInfos()).thenReturn(List.of(neverCachedTl));
        when(summary.getLOTLInfos()).thenReturn(List.of(lotlInfo));
        when(summary.getOtherTLInfos()).thenReturn(List.of());
        when(trustListValidationJob.getSummary()).thenReturn(summary);

        // Act
        SyncOutcome outcome = adapter.fetchAnchorsFromCache();

        // Assert
        verify(trustListValidationJob).offlineRefresh();
        assertThat(outcome.anchors()).isEmpty();
        assertThat(outcome.rejections())
                .extracting(r -> r.listIdentifier(), r -> r.reason())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "https://tl.es/tl.xml", RejectionReason.UNREACHABLE));
    }

    @Test
    void fetchAnchors_NationalListNextUpdatePassed_AcceptsItAndReportsItAsStale() {
        // Arrange: EC-02 — a next-update date already in the past does not reject the list (its
        // anchors are still gathered), but NFR-O-227-01 requires the condition to be visible
        // beyond the log, via SyncOutcome#listsWithStaleNextUpdate().
        LOTLInfo lotlInfo = validLotlInfo("https://ec.europa.eu/lotl");

        TLInfo staleTl = mockTlInfo("https://tl.stale/tl.xml");
        DownloadInfoRecord download = mockDownloadInfo(false, null);
        ValidationInfoRecord validation = mockValidationInfo(false);
        when(staleTl.getDownloadCacheInfo()).thenReturn(download);
        when(staleTl.getValidationCacheInfo()).thenReturn(validation);
        TrustService service = trustService(GRANTED_STATUS, Instant.parse("2026-01-01T00:00:00Z"), null);
        TrustServiceProvider provider = provider("ES", service);
        ParsingInfoRecord parsingInfo = org.mockito.Mockito.mock(ParsingInfoRecord.class);
        when(parsingInfo.getNextUpdateDate()).thenReturn(Date.from(Instant.now().minusSeconds(86_400)));
        when(parsingInfo.getTerritory()).thenReturn("ES");
        when(parsingInfo.getTrustServiceProviders()).thenReturn(List.of(provider));
        when(staleTl.getParsingCacheInfo()).thenReturn(parsingInfo);

        when(lotlInfo.getTLInfos()).thenReturn(List.of(staleTl));
        when(summary.getLOTLInfos()).thenReturn(List.of(lotlInfo));
        when(summary.getOtherTLInfos()).thenReturn(List.of());
        when(trustListValidationJob.getSummary()).thenReturn(summary);

        try (MockedStatic<DSSUtils> dssUtils = mockStatic(DSSUtils.class)) {
            dssUtils.when(() -> DSSUtils.convertToPEM(any())).thenReturn("pem");

            // Act
            SyncOutcome outcome = adapter.fetchAnchors();

            // Assert
            assertThat(outcome.rejections()).isEmpty();
            assertThat(outcome.anchors()).hasSize(1);
            assertThat(outcome.hasStaleNextUpdates()).isTrue();
            assertThat(outcome.listsWithStaleNextUpdate()).containsExactly("https://tl.stale/tl.xml");
        }
    }

    @Test
    void fetchAnchors_WithdrawnService_KeepsAnchorInsteadOfFiltering() {
        // Arrange: AD-4/AC-03 — synchronisation never filters by status.
        LOTLInfo lotlInfo = validLotlInfo("https://ec.europa.eu/lotl");
        TLInfo tlInfo = validTlInfo("https://tl.es/tl.xml");
        validParsingInfo(tlInfo, "ES", List.of(provider(
                "ES",
                trustService(WITHDRAWN_STATUS, Instant.parse("2026-01-01T00:00:00Z"),
                        Instant.parse("2026-06-01T00:00:00Z")))));
        when(lotlInfo.getTLInfos()).thenReturn(List.of(tlInfo));
        when(summary.getLOTLInfos()).thenReturn(List.of(lotlInfo));
        when(summary.getOtherTLInfos()).thenReturn(List.of());
        when(trustListValidationJob.getSummary()).thenReturn(summary);

        try (MockedStatic<DSSUtils> dssUtils = mockStatic(DSSUtils.class)) {
            dssUtils.when(() -> DSSUtils.convertToPEM(any())).thenReturn("pem");

            // Act
            SyncOutcome outcome = adapter.fetchAnchors();

            // Assert: the anchor is kept, with its status and window, not dropped.
            assertThat(outcome.anchors()).hasSize(1);
            TrustAnchor anchor = outcome.anchors().get(0);
            assertThat(anchor.status()).isEqualTo(TrustServiceStatus.WITHDRAWN);
            assertThat(anchor.statusValidUntil()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
        }
    }

    // --- fixtures -----------------------------------------------------------------------

    private LOTLInfo validLotlInfo(String url) {
        LOTLInfo info = mockLotlInfo(url);
        DownloadInfoRecord download = mockDownloadInfo(false, null);
        ValidationInfoRecord validation = mockValidationInfo(false);
        when(info.getDownloadCacheInfo()).thenReturn(download);
        when(info.getValidationCacheInfo()).thenReturn(validation);
        ParsingInfoRecord parsingInfo = org.mockito.Mockito.mock(ParsingInfoRecord.class);
        when(parsingInfo.getNextUpdateDate()).thenReturn(Date.from(Instant.now().plusSeconds(86_400)));
        when(info.getParsingCacheInfo()).thenReturn(parsingInfo);
        return info;
    }

    private TLInfo validTlInfo(String url) {
        TLInfo info = mockTlInfo(url);
        DownloadInfoRecord download = mockDownloadInfo(false, null);
        ValidationInfoRecord validation = mockValidationInfo(false);
        when(info.getDownloadCacheInfo()).thenReturn(download);
        when(info.getValidationCacheInfo()).thenReturn(validation);
        return info;
    }

    private LOTLInfo mockLotlInfo(String url) {
        LOTLInfo info = org.mockito.Mockito.mock(LOTLInfo.class);
        when(info.getUrl()).thenReturn(url);
        return info;
    }

    private TLInfo mockTlInfo(String url) {
        TLInfo info = org.mockito.Mockito.mock(TLInfo.class);
        when(info.getUrl()).thenReturn(url);
        return info;
    }

    private DownloadInfoRecord mockDownloadInfo(boolean error, String exceptionMessage) {
        DownloadInfoRecord info = org.mockito.Mockito.mock(DownloadInfoRecord.class);
        when(info.isError()).thenReturn(error);
        if (error) {
            when(info.getExceptionMessage()).thenReturn(exceptionMessage);
        }
        return info;
    }

    private ValidationInfoRecord mockValidationInfo(boolean invalid) {
        ValidationInfoRecord info = org.mockito.Mockito.mock(ValidationInfoRecord.class);
        when(info.isInvalid()).thenReturn(invalid);
        return info;
    }

    private ParsingInfoRecord validParsingInfo(TLInfo info, String territory, List<TrustServiceProvider> providers) {
        ParsingInfoRecord parsingInfo = org.mockito.Mockito.mock(ParsingInfoRecord.class);
        when(parsingInfo.getNextUpdateDate()).thenReturn(Date.from(Instant.now().plusSeconds(86_400)));
        when(parsingInfo.getTerritory()).thenReturn(territory);
        when(parsingInfo.getTrustServiceProviders()).thenReturn(providers);
        when(info.getParsingCacheInfo()).thenReturn(parsingInfo);
        return parsingInfo;
    }

    private TrustServiceProvider provider(String territory, TrustService... services) {
        TrustServiceProvider provider = new TrustServiceProvider();
        provider.setTerritory(territory);
        provider.setServices(List.of(services));
        return provider;
    }

    private TrustService trustService(String statusUri, Instant startDate, Instant endDate) {
        CertificateToken certificate = org.mockito.Mockito.mock(CertificateToken.class);
        X500PrincipalHelper subjectHelper = org.mockito.Mockito.mock(X500PrincipalHelper.class);
        when(subjectHelper.getRFC2253()).thenReturn("CN=Test CA");
        when(certificate.getSubject()).thenReturn(subjectHelper);

        TrustServiceStatusAndInformationExtensions statusPeriod =
                new TrustServiceStatusAndInformationExtensions.TrustServiceStatusAndInformationExtensionsBuilder()
                        .setType(SERVICE_TYPE)
                        .setStatus(statusUri)
                        .setStartDate(Date.from(startDate))
                        .setEndDate(endDate == null ? null : Date.from(endDate))
                        .build();

        return new TrustService.TrustServiceBuilder()
                .setCertificates(List.of(certificate))
                .setStatusAndInformationExtensions(new TimeDependentValues<>(List.of(statusPeriod)))
                .build();
    }
}
