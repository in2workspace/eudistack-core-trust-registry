package es.in2.trustregistry.anchors.infrastructure.adapter.dss;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DssOfficialTrustListAdapterTest {

    @Test
    void fetchAnchors_SynchronisationNotImplementedYet_ReturnsNoAnchorsInsteadOfFailing() {
        // Arrange
        DssOfficialTrustListAdapter adapter = new DssOfficialTrustListAdapter();

        // Act & Assert: the stub must fail closed (no anchors), never grant trust it cannot prove.
        assertThat(adapter.fetchAnchors()).isEmpty();
    }
}
