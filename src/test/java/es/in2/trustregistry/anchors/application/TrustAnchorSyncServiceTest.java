package es.in2.trustregistry.anchors.application;

import es.in2.trustregistry.anchors.domain.model.ListRejection;
import es.in2.trustregistry.anchors.domain.model.ListRejection.RejectionReason;
import es.in2.trustregistry.anchors.domain.model.SyncOutcome;
import es.in2.trustregistry.anchors.domain.model.TrustAnchor;
import es.in2.trustregistry.anchors.domain.model.TrustAnchorSet;
import es.in2.trustregistry.anchors.domain.model.TrustServiceStatus;
import es.in2.trustregistry.anchors.domain.port.OfficialTrustListPort;
import es.in2.trustregistry.anchors.domain.port.TrustAnchorRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TrustAnchorSyncServiceTest {

    private static final Instant EFFECTIVE = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant SYNC_INSTANT = Instant.parse("2026-08-27T10:00:00Z");

    @Mock
    private OfficialTrustListPort officialTrustList;

    @Mock
    private TrustAnchorRepositoryPort repository;

    @Captor
    private ArgumentCaptor<TrustAnchorSet> storedAnchorSet;

    private TrustAnchorSyncService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(SYNC_INSTANT, ZoneOffset.UTC);
        service = new TrustAnchorSyncService(officialTrustList, repository, clock);
    }

    private static TrustAnchor anchor(String subject, TrustServiceStatus status) {
        return new TrustAnchor(subject, "pem", "ES", "serviceType", status, EFFECTIVE, null);
    }

    @Test
    void synchronise_MixedStatuses_StoresEveryAnchorWithItsStatusAndTheSyncInstant() {
        // Arrange: nothing is dropped here. An anchor that is no longer granted still answers
        // whether the service was qualified at the date of a past act, and filtering at this
        // point would destroy that. Usability is resolved on query, not on synchronisation.
        SyncOutcome outcome = new SyncOutcome(List.of(
                anchor("CN=Granted", TrustServiceStatus.GRANTED),
                anchor("CN=Withdrawn", TrustServiceStatus.WITHDRAWN),
                anchor("CN=Suspended", TrustServiceStatus.SUSPENDED)), List.of());
        when(officialTrustList.fetchAnchors()).thenReturn(outcome);

        // Act
        SyncOutcome result = service.synchronise();

        // Assert
        assertThat(result).isSameAs(outcome);
        verify(repository).replaceAll(storedAnchorSet.capture());
        TrustAnchorSet stored = storedAnchorSet.getValue();
        assertThat(stored.anchors())
                .extracting(TrustAnchor::subject)
                .containsExactly("CN=Granted", "CN=Withdrawn", "CN=Suspended");
        assertThat(stored.lastSuccessfulSyncAt()).isEqualTo(SYNC_INSTANT);
        assertThat(stored.isNeverSynced()).isFalse();
    }

    @Test
    void synchronise_SourceReturnsNothing_StoresASyncedEmptySetNotANeverSyncedOne() {
        // Arrange: EC-04 distinguishes "synced and empty" from "never synced" — a successful
        // run that found no anchors is still a dated outcome.
        SyncOutcome outcome = new SyncOutcome(List.of(), List.of());
        when(officialTrustList.fetchAnchors()).thenReturn(outcome);

        // Act
        service.synchronise();

        // Assert
        verify(repository).replaceAll(storedAnchorSet.capture());
        TrustAnchorSet stored = storedAnchorSet.getValue();
        assertThat(stored.anchors()).isEmpty();
        assertThat(stored.isNeverSynced()).isFalse();
        assertThat(stored.lastSuccessfulSyncAt()).isEqualTo(SYNC_INSTANT);
    }

    @Test
    void synchronise_ListRejected_PropagatesTheRejectionInTheReturnedOutcome() {
        // Arrange: ES-03/AC-02 — the caller must learn about a rejected list, not just anchors.
        ListRejection rejection = new ListRejection("ES", RejectionReason.SIGNATURE_INVALID,
                "signature does not verify");
        SyncOutcome outcome = new SyncOutcome(List.of(anchor("CN=Other", TrustServiceStatus.GRANTED)),
                List.of(rejection));
        when(officialTrustList.fetchAnchors()).thenReturn(outcome);

        // Act
        SyncOutcome result = service.synchronise();

        // Assert
        assertThat(result.rejections()).containsExactly(rejection);
        assertThat(result.hasRejections()).isTrue();
    }

    @Test
    void synchronise_SourceFailsMidRun_LeavesThePreviousSetUntouched() {
        // Arrange: ES-03 — a failure never reaches replaceAll, so the previous set survives
        // without any explicit rollback.
        when(officialTrustList.fetchAnchors()).thenThrow(new IllegalStateException("LOTL unreachable"));

        // Act & Assert
        assertThatThrownBy(() -> service.synchronise()).isInstanceOf(IllegalStateException.class);
        verify(repository, never()).replaceAll(ArgumentMatchers.any());
    }

    @Test
    void currentAnchors_RepositoryHoldsASyncedSet_ReturnsItsAnchors() {
        // Arrange
        TrustAnchor granted = anchor("CN=Granted", TrustServiceStatus.GRANTED);
        when(repository.current()).thenReturn(new TrustAnchorSet(List.of(granted), SYNC_INSTANT));

        // Act
        List<TrustAnchor> anchors = service.currentAnchors();

        // Assert
        assertThat(anchors).containsExactly(granted);
    }

    @Test
    void currentAnchors_RepositoryNeverSynced_ReturnsAnEmptyList() {
        // Arrange
        when(repository.current()).thenReturn(TrustAnchorSet.neverSynced());

        // Act
        List<TrustAnchor> anchors = service.currentAnchors();

        // Assert
        assertThat(anchors).isEmpty();
    }
}
