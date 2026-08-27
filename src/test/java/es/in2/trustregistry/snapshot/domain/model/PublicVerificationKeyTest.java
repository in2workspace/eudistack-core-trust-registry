package es.in2.trustregistry.snapshot.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicVerificationKeyTest {

    private static final String KEY_ID = "trust-registry-2026";
    private static final String CURVE = "P-256";
    private static final String X = "MKBCTNIcKUSDii11ySs3526iDZ8AiTo7Tu6KPAqv7D4";
    private static final String Y = "4Etl6SRW2YiLUrN5vfvVHuhp7x8PxltmWWlbbM4IFyM";

    @Test
    void constructor_ValidFields_CreatesPublicVerificationKey() {
        // Act
        PublicVerificationKey key = new PublicVerificationKey(KEY_ID, CURVE, X, Y);

        // Assert
        assertThat(key.keyId()).isEqualTo(KEY_ID);
        assertThat(key.curve()).isEqualTo(CURVE);
        assertThat(key.x()).isEqualTo(X);
        assertThat(key.y()).isEqualTo(Y);
    }

    @Test
    void constructor_NullKeyId_ThrowsNullPointerException() {
        // Act & Assert
        assertThatThrownBy(() -> new PublicVerificationKey(null, CURVE, X, Y))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("keyId");
    }

    @Test
    void constructor_BlankKeyId_ThrowsIllegalArgumentException() {
        // Act & Assert
        assertThatThrownBy(() -> new PublicVerificationKey(" ", CURVE, X, Y))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keyId");
    }

    @Test
    void constructor_NullCurve_ThrowsNullPointerException() {
        // Act & Assert
        assertThatThrownBy(() -> new PublicVerificationKey(KEY_ID, null, X, Y))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("curve");
    }

    @Test
    void constructor_BlankCurve_ThrowsIllegalArgumentException() {
        // Act & Assert
        assertThatThrownBy(() -> new PublicVerificationKey(KEY_ID, " ", X, Y))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("curve");
    }

    @Test
    void constructor_NullX_ThrowsNullPointerException() {
        // Act & Assert
        assertThatThrownBy(() -> new PublicVerificationKey(KEY_ID, CURVE, null, Y))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("x");
    }

    @Test
    void constructor_BlankX_ThrowsIllegalArgumentException() {
        // Act & Assert
        assertThatThrownBy(() -> new PublicVerificationKey(KEY_ID, CURVE, " ", Y))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x");
    }

    @Test
    void constructor_NullY_ThrowsNullPointerException() {
        // Act & Assert
        assertThatThrownBy(() -> new PublicVerificationKey(KEY_ID, CURVE, X, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("y");
    }

    @Test
    void constructor_BlankY_ThrowsIllegalArgumentException() {
        // Act & Assert
        assertThatThrownBy(() -> new PublicVerificationKey(KEY_ID, CURVE, X, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("y");
    }
}
