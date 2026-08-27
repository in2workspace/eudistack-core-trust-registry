package es.in2.trustregistry.anchors.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ListRejectionTest {

    @Test
    void constructor_NullListIdentifier_ThrowsNullPointerException() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ListRejection(null, ListRejection.RejectionReason.SIGNATURE_INVALID, "detail"));
    }

    @Test
    void constructor_NullReason_ThrowsNullPointerException() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ListRejection("ES", null, "detail"));
    }

    @Test
    void constructor_NullDetail_ThrowsNullPointerException() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ListRejection("ES", ListRejection.RejectionReason.UNREACHABLE, null));
    }

    @Test
    void constructor_ValidArguments_CreatesRejectionWithGivenFields() {
        // Act
        ListRejection rejection = new ListRejection("ES", ListRejection.RejectionReason.SIGNATURE_INVALID,
                "signature does not verify against the official signing certificates");

        // Assert
        assertThat(rejection.listIdentifier()).isEqualTo("ES");
        assertThat(rejection.reason()).isEqualTo(ListRejection.RejectionReason.SIGNATURE_INVALID);
        assertThat(rejection.detail()).isEqualTo("signature does not verify against the official signing certificates");
    }
}
