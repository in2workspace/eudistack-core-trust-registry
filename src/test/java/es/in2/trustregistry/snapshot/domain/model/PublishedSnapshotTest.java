package es.in2.trustregistry.snapshot.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublishedSnapshotTest {

    private static final SnapshotFingerprint FINGERPRINT = new SnapshotFingerprint("abc123");
    private static final String SIGNED_DOCUMENT = "header.payload.signature";

    @Test
    void constructor_ValidFields_CreatesPublishedSnapshot() {
        // Act
        PublishedSnapshot published = new PublishedSnapshot("cgcom", 1L, FINGERPRINT, SIGNED_DOCUMENT);

        // Assert
        assertThat(published.tenantId()).isEqualTo("cgcom");
        assertThat(published.version()).isEqualTo(1L);
        assertThat(published.fingerprint()).isEqualTo(FINGERPRINT);
        assertThat(published.signedDocument()).isEqualTo(SIGNED_DOCUMENT);
    }

    @Test
    void constructor_NullTenantId_ThrowsNullPointerException() {
        // Act & Assert
        assertThatThrownBy(() -> new PublishedSnapshot(null, 1L, FINGERPRINT, SIGNED_DOCUMENT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void constructor_NullFingerprint_ThrowsNullPointerException() {
        // Act & Assert
        assertThatThrownBy(() -> new PublishedSnapshot("cgcom", 1L, null, SIGNED_DOCUMENT))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fingerprint");
    }

    @Test
    void constructor_NullSignedDocument_ThrowsNullPointerException() {
        // Act & Assert
        assertThatThrownBy(() -> new PublishedSnapshot("cgcom", 1L, FINGERPRINT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("signedDocument");
    }

    @Test
    void constructor_NegativeVersion_ThrowsIllegalArgumentException() {
        // Act & Assert
        assertThatThrownBy(() -> new PublishedSnapshot("cgcom", -1L, FINGERPRINT, SIGNED_DOCUMENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    @Test
    void constructor_ZeroVersion_CreatesPublishedSnapshot() {
        // Act
        PublishedSnapshot published = new PublishedSnapshot("cgcom", 0L, FINGERPRINT, SIGNED_DOCUMENT);

        // Assert
        assertThat(published.version()).isZero();
    }
}
