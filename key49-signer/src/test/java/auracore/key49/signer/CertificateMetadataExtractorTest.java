package auracore.key49.signer;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests unitarios para CertificateMetadataExtractor.
 */
class CertificateMetadataExtractorTest {

    private static final String TEST_CERT_PATH = "/test-cert.p12";
    private static final char[] TEST_PASSWORD = "test1234".toCharArray();

    private byte[] loadTestCertificate() {
        try (InputStream is = getClass().getResourceAsStream(TEST_CERT_PATH)) {
            if (is == null) {
                throw new IllegalStateException("Test certificate not found: " + TEST_CERT_PATH);
            }
            return is.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Extracción exitosa ──

    @Nested
    @DisplayName("extract — caso exitoso")
    class ExtractSuccess {

        @Test
        @DisplayName("extrae subject del certificado")
        void extractsSubject() {
            var p12 = loadTestCertificate();
            var metadata = CertificateMetadataExtractor.extract(p12, TEST_PASSWORD);

            assertNotNull(metadata.subject());
            assertFalse(metadata.subject().isBlank());
        }

        @Test
        @DisplayName("extrae serial en hexadecimal")
        void extractsSerial() {
            var p12 = loadTestCertificate();
            var metadata = CertificateMetadataExtractor.extract(p12, TEST_PASSWORD);

            assertNotNull(metadata.serial());
            assertFalse(metadata.serial().isBlank());
        }

        @Test
        @DisplayName("extrae fecha de expiración")
        void extractsExpiration() {
            var p12 = loadTestCertificate();
            var metadata = CertificateMetadataExtractor.extract(p12, TEST_PASSWORD);

            assertNotNull(metadata.expiresAt());
        }

        @Test
        @DisplayName("extrae issuer del certificado")
        void extractsIssuer() {
            var p12 = loadTestCertificate();
            var metadata = CertificateMetadataExtractor.extract(p12, TEST_PASSWORD);

            assertNotNull(metadata.issuer());
            assertFalse(metadata.issuer().isBlank());
        }

        @Test
        @DisplayName("certificado de prueba es válido (no expirado)")
        void testCertIsValid() {
            var p12 = loadTestCertificate();
            var metadata = CertificateMetadataExtractor.extract(p12, TEST_PASSWORD);

            assertTrue(metadata.valid(), "Test certificate should not be expired");
        }

        @Test
        @DisplayName("daysUntilExpiration retorna valor positivo para cert válido")
        void daysUntilExpirationPositive() {
            var p12 = loadTestCertificate();
            var metadata = CertificateMetadataExtractor.extract(p12, TEST_PASSWORD);

            assertTrue(metadata.daysUntilExpiration() > 0,
                    "Days until expiration should be positive for a valid certificate");
        }

        @Test
        @DisplayName("metadata es consistente entre invocaciones")
        void metadataIsConsistent() {
            var p12 = loadTestCertificate();
            var m1 = CertificateMetadataExtractor.extract(p12, TEST_PASSWORD);
            var m2 = CertificateMetadataExtractor.extract(p12, TEST_PASSWORD);

            assertEquals(m1.subject(), m2.subject());
            assertEquals(m1.serial(), m2.serial());
            assertEquals(m1.expiresAt(), m2.expiresAt());
            assertEquals(m1.issuer(), m2.issuer());
        }

        @Test
        @DisplayName("serial está en formato hexadecimal uppercase")
        void serialIsHexUpperCase() {
            var p12 = loadTestCertificate();
            var metadata = CertificateMetadataExtractor.extract(p12, TEST_PASSWORD);

            assertTrue(metadata.serial().matches("^[0-9A-F]+$"),
                    "Serial should be uppercase hexadecimal: " + metadata.serial());
        }
    }

    // ── Validación de inputs ──

    @Nested
    @DisplayName("extract — validación de inputs")
    class InputValidation {

        @Test
        @DisplayName("falla con p12 null")
        void failsWithNullP12() {
            var ex = assertThrows(SigningException.class,
                    () -> CertificateMetadataExtractor.extract(null, TEST_PASSWORD));
            assertTrue(ex.getMessage().contains("empty or null"));
        }

        @Test
        @DisplayName("falla con p12 vacío")
        void failsWithEmptyP12() {
            var ex = assertThrows(SigningException.class,
                    () -> CertificateMetadataExtractor.extract(new byte[0], TEST_PASSWORD));
            assertTrue(ex.getMessage().contains("empty or null"));
        }

        @Test
        @DisplayName("falla con password null")
        void failsWithNullPassword() {
            var p12 = loadTestCertificate();
            var ex = assertThrows(SigningException.class,
                    () -> CertificateMetadataExtractor.extract(p12, null));
            assertTrue(ex.getMessage().contains("empty or null"));
        }

        @Test
        @DisplayName("falla con password vacío")
        void failsWithEmptyPassword() {
            var p12 = loadTestCertificate();
            var ex = assertThrows(SigningException.class,
                    () -> CertificateMetadataExtractor.extract(p12, new char[0]));
            assertTrue(ex.getMessage().contains("empty or null"));
        }

        @Test
        @DisplayName("falla con contraseña incorrecta")
        void failsWithWrongPassword() {
            var p12 = loadTestCertificate();
            var ex = assertThrows(SigningException.class,
                    () -> CertificateMetadataExtractor.extract(p12, "wrong_password".toCharArray()));
            assertTrue(ex.getMessage().contains("wrong password") || ex.getMessage().contains("Failed"));
        }

        @Test
        @DisplayName("falla con bytes inválidos (no es PKCS#12)")
        void failsWithInvalidBytes() {
            var invalidBytes = "not a certificate".getBytes();
            assertThrows(SigningException.class,
                    () -> CertificateMetadataExtractor.extract(invalidBytes, TEST_PASSWORD));
        }

        @Test
        @DisplayName("falla con bytes aleatorios")
        void failsWithRandomBytes() {
            var random = new byte[]{0x30, 0x15, 0x02, 0x01, 0x03, 0x30};
            assertThrows(SigningException.class,
                    () -> CertificateMetadataExtractor.extract(random, TEST_PASSWORD));
        }
    }

    // ── CertificateMetadata record ──

    @Nested
    @DisplayName("CertificateMetadata record")
    class MetadataRecord {

        @Test
        @DisplayName("daysUntilExpiration negativo para cert expirado")
        void daysNegativeForExpired() {
            var metadata = new CertificateMetadataExtractor.CertificateMetadata(
                    "CN=Test", "ABC123",
                    Instant.now().minusSeconds(86400 * 30), // 30 days ago
                    "CN=CA", false);

            assertTrue(metadata.daysUntilExpiration() < 0);
        }

        @Test
        @DisplayName("daysUntilExpiration cero o positivo para cert futuro")
        void daysPositiveForFuture() {
            var metadata = new CertificateMetadataExtractor.CertificateMetadata(
                    "CN=Test", "ABC123",
                    Instant.now().plusSeconds(86400 * 365), // 1 year ahead
                    "CN=CA", true);

            assertTrue(metadata.daysUntilExpiration() > 300);
        }

        @Test
        @DisplayName("record equals funciona correctamente")
        void recordEquals() {
            var exp = Instant.parse("2027-01-01T00:00:00Z");
            var m1 = new CertificateMetadataExtractor.CertificateMetadata(
                    "CN=Test", "ABC", exp, "CN=CA", true);
            var m2 = new CertificateMetadataExtractor.CertificateMetadata(
                    "CN=Test", "ABC", exp, "CN=CA", true);

            assertEquals(m1, m2);
        }
    }
}
