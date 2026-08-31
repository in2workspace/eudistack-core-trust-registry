package es.in2.trustregistry.snapshot.application;

import es.in2.trustregistry.snapshot.domain.exception.SnapshotPublicationConflictException;
import es.in2.trustregistry.snapshot.domain.model.PublishedSnapshot;
import es.in2.trustregistry.snapshot.domain.model.SnapshotFingerprint;
import es.in2.trustregistry.snapshot.domain.model.TrustSnapshot;
import es.in2.trustregistry.snapshot.domain.model.TrustProfile;
import es.in2.trustregistry.snapshot.domain.port.PublishedSnapshotRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SnapshotVersionResolver}: the isolated versioning policy behind AD-1 and
 * the compare-and-swap retry described in {@code technical-design.md} §3.4.1 RC-1 (AC-05, EC-03).
 *
 * <p>{@code snapshotFactory} and {@code signer} are asserted via interaction counts, not return
 * values: what matters here is <em>whether</em> they were invoked (AD-1's "identical fingerprint
 * never re-signs" guarantee), not what a real {@link TrustSnapshot}/signature looks like — that
 * belongs to {@code TrustSnapshotServiceTest} (task 18) and {@code JwsSnapshotSignerTest} (task
 * 19).
 */
@ExtendWith(MockitoExtension.class)
class SnapshotVersionResolverTest {

    private static final String TENANT = "cgcom";
    private static final SnapshotFingerprint FINGERPRINT_A = new SnapshotFingerprint("fingerprint-a");
    private static final SnapshotFingerprint FINGERPRINT_B = new SnapshotFingerprint("fingerprint-b");

    @Mock
    private PublishedSnapshotRepositoryPort repository;

    private SnapshotVersionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SnapshotVersionResolver(repository);
    }

    private static PublishedSnapshot published(long version, SnapshotFingerprint fingerprint) {
        return new PublishedSnapshot(TENANT, version, fingerprint, "signed-document-v" + version);
    }

    @SuppressWarnings("unchecked")
    private static LongFunction<TrustSnapshot> factory() {
        return mock(LongFunction.class);
    }

    @SuppressWarnings("unchecked")
    private static Function<TrustSnapshot, String> signer() {
        return mock(Function.class);
    }

    private static TrustSnapshot snapshot(long version) {
        return new TrustSnapshot(TENANT, version, Instant.parse("2026-08-28T10:00:00Z"), 86400,
                List.of(), List.of(), TrustProfile.PRODUCTION, false, null);
    }

    @Test
    void resolve_FingerprintUnchanged_ReturnsPreviousPublicationWithoutBuildingOrSigning() {
        // Arrange
        PublishedSnapshot previous = published(12L, FINGERPRINT_A);
        when(repository.findByTenant(TENANT)).thenReturn(Optional.of(previous));
        LongFunction<TrustSnapshot> snapshotFactory = factory();
        Function<TrustSnapshot, String> signer = signer();

        // Act
        PublishedSnapshot result = resolver.resolve(TENANT, FINGERPRINT_A, snapshotFactory, signer);

        // Assert
        assertThat(result).isSameAs(previous);
        verifyNoInteractions(snapshotFactory, signer);
        verify(repository, never()).replaceIfVersionIs(eq(TENANT), anyLong(), any());
    }

    @Test
    void resolve_FingerprintChanged_SealsPreviousVersionPlusOne() {
        // Arrange
        PublishedSnapshot previous = published(12L, FINGERPRINT_A);
        when(repository.findByTenant(TENANT)).thenReturn(Optional.of(previous));

        LongFunction<TrustSnapshot> snapshotFactory = factory();
        when(snapshotFactory.apply(13L)).thenReturn(snapshot(13L));
        Function<TrustSnapshot, String> signer = signer();
        when(signer.apply(snapshot(13L))).thenReturn("signed-13");

        PublishedSnapshot sealed = published(13L, FINGERPRINT_B);
        when(repository.replaceIfVersionIs(TENANT, 12L, new PublishedSnapshot(TENANT, 13L, FINGERPRINT_B, "signed-13")))
                .thenReturn(new PublishedSnapshotRepositoryPort.CasResult.Applied(sealed));

        // Act
        PublishedSnapshot result = resolver.resolve(TENANT, FINGERPRINT_B, snapshotFactory, signer);

        // Assert
        assertThat(result).isSameAs(sealed);
        verify(snapshotFactory, times(1)).apply(13L);
        verify(signer, times(1)).apply(snapshot(13L));
    }

    @Test
    void resolve_NoPriorPublication_SealsVersionOne() {
        // Arrange
        when(repository.findByTenant(TENANT)).thenReturn(Optional.empty());

        LongFunction<TrustSnapshot> snapshotFactory = factory();
        when(snapshotFactory.apply(1L)).thenReturn(snapshot(1L));
        Function<TrustSnapshot, String> signer = signer();
        when(signer.apply(snapshot(1L))).thenReturn("signed-1");

        PublishedSnapshot sealed = published(1L, FINGERPRINT_A);
        when(repository.replaceIfVersionIs(TENANT, 0L, new PublishedSnapshot(TENANT, 1L, FINGERPRINT_A, "signed-1")))
                .thenReturn(new PublishedSnapshotRepositoryPort.CasResult.Applied(sealed));

        // Act
        PublishedSnapshot result = resolver.resolve(TENANT, FINGERPRINT_A, snapshotFactory, signer);

        // Assert
        assertThat(result.version()).isEqualTo(1L);
    }

    @Test
    void resolve_CasConflictWithWinnerMatchingCandidateFingerprint_ReturnsWinnerWithoutRetrying() {
        // Arrange: two concurrent requests for the same tenant, no real source change (EC-03) —
        // this writer loses the CAS, but the winner published exactly the same content.
        PublishedSnapshot previous = published(12L, FINGERPRINT_A);
        when(repository.findByTenant(TENANT)).thenReturn(Optional.of(previous));

        LongFunction<TrustSnapshot> snapshotFactory = factory();
        when(snapshotFactory.apply(13L)).thenReturn(snapshot(13L));
        Function<TrustSnapshot, String> signer = signer();
        when(signer.apply(snapshot(13L))).thenReturn("signed-13");

        PublishedSnapshot winner = published(13L, FINGERPRINT_B);
        when(repository.replaceIfVersionIs(eq(TENANT), eq(12L), any()))
                .thenReturn(new PublishedSnapshotRepositoryPort.CasResult.Conflict(winner));

        // Act
        PublishedSnapshot result = resolver.resolve(TENANT, FINGERPRINT_B, snapshotFactory, signer);

        // Assert
        assertThat(result).isSameAs(winner);
        verify(repository, times(1)).replaceIfVersionIs(eq(TENANT), anyLong(), any());
        verify(snapshotFactory, times(1)).apply(anyLong());
    }

    @Test
    void resolve_CasConflictWithWinnerFingerprintDiffering_RetriesOnceAgainstWinnerVersion() {
        // Arrange
        PublishedSnapshot previous = published(12L, FINGERPRINT_A);
        when(repository.findByTenant(TENANT)).thenReturn(Optional.of(previous));

        SnapshotFingerprint candidateFingerprint = new SnapshotFingerprint("fingerprint-c");
        LongFunction<TrustSnapshot> snapshotFactory = factory();
        when(snapshotFactory.apply(13L)).thenReturn(snapshot(13L));
        when(snapshotFactory.apply(14L)).thenReturn(snapshot(14L));
        Function<TrustSnapshot, String> signer = signer();
        when(signer.apply(snapshot(13L))).thenReturn("signed-13");
        when(signer.apply(snapshot(14L))).thenReturn("signed-14");

        PublishedSnapshot winner = published(13L, FINGERPRINT_B);
        PublishedSnapshot retrySealed = published(14L, candidateFingerprint);

        when(repository.replaceIfVersionIs(TENANT, 12L, new PublishedSnapshot(TENANT, 13L, candidateFingerprint, "signed-13")))
                .thenReturn(new PublishedSnapshotRepositoryPort.CasResult.Conflict(winner));
        when(repository.replaceIfVersionIs(TENANT, 13L, new PublishedSnapshot(TENANT, 14L, candidateFingerprint, "signed-14")))
                .thenReturn(new PublishedSnapshotRepositoryPort.CasResult.Applied(retrySealed));

        // Act
        PublishedSnapshot result = resolver.resolve(TENANT, candidateFingerprint, snapshotFactory, signer);

        // Assert
        assertThat(result).isSameAs(retrySealed);
        verify(repository, times(2)).replaceIfVersionIs(eq(TENANT), anyLong(), any());
        verify(snapshotFactory, times(1)).apply(13L);
        verify(snapshotFactory, times(1)).apply(14L);
    }

    @Test
    void resolve_RetryAlsoConflicts_ThrowsWithoutAdvancingVersion() {
        // Arrange: three writers race inside one request lifetime — RC-1 allows exactly one retry.
        PublishedSnapshot previous = published(12L, FINGERPRINT_A);
        when(repository.findByTenant(TENANT)).thenReturn(Optional.of(previous));

        SnapshotFingerprint candidateFingerprint = new SnapshotFingerprint("fingerprint-c");
        LongFunction<TrustSnapshot> snapshotFactory = factory();
        when(snapshotFactory.apply(13L)).thenReturn(snapshot(13L));
        when(snapshotFactory.apply(14L)).thenReturn(snapshot(14L));
        Function<TrustSnapshot, String> signer = signer();
        when(signer.apply(snapshot(13L))).thenReturn("signed-13");
        when(signer.apply(snapshot(14L))).thenReturn("signed-14");

        PublishedSnapshot firstWinner = published(13L, FINGERPRINT_B);
        PublishedSnapshot secondWinner = published(14L, new SnapshotFingerprint("fingerprint-d"));

        when(repository.replaceIfVersionIs(TENANT, 12L, new PublishedSnapshot(TENANT, 13L, candidateFingerprint, "signed-13")))
                .thenReturn(new PublishedSnapshotRepositoryPort.CasResult.Conflict(firstWinner));
        when(repository.replaceIfVersionIs(TENANT, 13L, new PublishedSnapshot(TENANT, 14L, candidateFingerprint, "signed-14")))
                .thenReturn(new PublishedSnapshotRepositoryPort.CasResult.Conflict(secondWinner));

        // Act & Assert
        assertThatThrownBy(() -> resolver.resolve(TENANT, candidateFingerprint, snapshotFactory, signer))
                .isInstanceOf(SnapshotPublicationConflictException.class)
                .hasMessageContaining(TENANT);

        verify(repository, times(2)).replaceIfVersionIs(eq(TENANT), anyLong(), any());
    }
}
