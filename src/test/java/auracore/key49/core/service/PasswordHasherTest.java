package auracore.key49.core.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitarios para PasswordHasher (BCrypt con BouncyCastle).
 */
class PasswordHasherTest {

    private PasswordHasher hasher;

    @BeforeEach
    void setUp() {
        hasher = new PasswordHasher();
    }

    @Test
    @DisplayName("hash genera un string BCrypt válido")
    void shouldGenerateBcryptHash() {
        var hash = hasher.hash("MyPassword123");
        assertNotNull(hash);
        assertTrue(hash.startsWith("$2") && hash.contains("$12$"), "Should be BCrypt cost 12: " + hash);
    }

    @Test
    @DisplayName("verify retorna true para contraseña correcta")
    void shouldVerifyCorrectPassword() {
        var password = "SecurePass!2025";
        var hash = hasher.hash(password);
        assertTrue(hasher.verify(password, hash));
    }

    @Test
    @DisplayName("verify retorna false para contraseña incorrecta")
    void shouldRejectWrongPassword() {
        var hash = hasher.hash("CorrectPassword");
        assertFalse(hasher.verify("WrongPassword", hash));
    }

    @Test
    @DisplayName("verify retorna false para valores nulos")
    void shouldReturnFalseForNulls() {
        assertFalse(hasher.verify(null, "$2a$12$xxxxx"));
        assertFalse(hasher.verify("password", null));
        assertFalse(hasher.verify(null, null));
    }

    @Test
    @DisplayName("hashes diferentes para la misma contraseña (salt aleatorio)")
    void shouldProduceDifferentHashesForSamePassword() {
        var password = "SamePassword";
        var hash1 = hasher.hash(password);
        var hash2 = hasher.hash(password);
        assertFalse(hash1.equals(hash2), "Each hash should use a different salt");
        // But both should verify
        assertTrue(hasher.verify(password, hash1));
        assertTrue(hasher.verify(password, hash2));
    }
}
