package auracore.key49.signer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests unitarios para CertificateEncryptor.
 */
class CertificateEncryptorTest {

    private static final String TEST_CERT_PATH = "/test-cert.p12";
    private static final char[] TEST_PASSWORD = "test1234".toCharArray();

    // ── Helpers ──

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

    private byte[] masterKey() {
        return CertificateEncryptor.generateMasterKey();
    }

    private String simpleInvoiceXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <factura id="comprobante" version="1.0.0">
                  <infoTributaria>
                    <razonSocial>EMPRESA DEMO S.A.</razonSocial>
                    <ruc>1790016919001</ruc>
                  </infoTributaria>
                </factura>
                """;
    }

    // ── Encrypt / Decrypt bytes ──

    @Nested
    @DisplayName("encrypt/decrypt bytes")
    class EncryptDecryptBytes {

        @Test
        @DisplayName("round-trip preserva datos originales")
        void roundTripPreservesData() {
            byte[] key = masterKey();
            byte[] original = "datos de prueba".getBytes();

            byte[] encrypted = CertificateEncryptor.encrypt(original, key);
            byte[] decrypted = CertificateEncryptor.decrypt(encrypted, key);

            assertArrayEquals(original, decrypted);
        }

        @Test
        @DisplayName("round-trip preserva contenido .p12")
        void roundTripPreservesP12() {
            byte[] key = masterKey();
            byte[] p12 = loadTestCertificate();

            byte[] encrypted = CertificateEncryptor.encrypt(p12, key);
            byte[] decrypted = CertificateEncryptor.decrypt(encrypted, key);

            assertArrayEquals(p12, decrypted);
        }

        @Test
        @DisplayName("ciphertext es diferente al plaintext")
        void ciphertextDiffersFromPlaintext() {
            byte[] key = masterKey();
            byte[] original = "datos secretos".getBytes();

            byte[] encrypted = CertificateEncryptor.encrypt(original, key);

            assertFalse(Arrays.equals(original, encrypted));
        }

        @Test
        @DisplayName("cada cifrado produce resultado distinto (IV aleatorio)")
        void eachEncryptionProducesDifferentResult() {
            byte[] key = masterKey();
            byte[] data = "datos repetidos".getBytes();

            byte[] encrypted1 = CertificateEncryptor.encrypt(data, key);
            byte[] encrypted2 = CertificateEncryptor.encrypt(data, key);

            assertFalse(Arrays.equals(encrypted1, encrypted2),
                    "Two encryptions of the same data should differ due to random IV");
        }

        @Test
        @DisplayName("clave maestra incorrecta falla al descifrar")
        void wrongKeyFailsToDecrypt() {
            byte[] key1 = masterKey();
            byte[] key2 = masterKey();
            byte[] data = "datos sensibles".getBytes();

            byte[] encrypted = CertificateEncryptor.encrypt(data, key1);

            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.decrypt(encrypted, key2));
        }

        @Test
        @DisplayName("datos corruptos fallan al descifrar")
        void corruptedDataFailsToDecrypt() {
            byte[] key = masterKey();
            byte[] data = "datos originales".getBytes();

            byte[] encrypted = CertificateEncryptor.encrypt(data, key);
            // Corrupt a byte in the ciphertext (after IV)
            encrypted[encrypted.length - 1] ^= 0xFF;

            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.decrypt(encrypted, key));
        }

        @Test
        @DisplayName("encrypted contiene IV de 12 bytes + ciphertext")
        void encryptedFormatContainsIvPlusCiphertext() {
            byte[] key = masterKey();
            byte[] data = "test".getBytes();

            byte[] encrypted = CertificateEncryptor.encrypt(data, key);

            // IV (12) + ciphertext (>= plaintext length) + GCM tag (16)
            assertTrue(encrypted.length >= 12 + data.length + 16,
                    "Encrypted output must contain IV + ciphertext + GCM tag");
        }
    }

    // ── Encrypt / Decrypt passwords ──

    @Nested
    @DisplayName("encrypt/decrypt passwords")
    class EncryptDecryptPasswords {

        @Test
        @DisplayName("round-trip preserva contraseña")
        void roundTripPreservesPassword() {
            byte[] key = masterKey();
            char[] password = "miContraseña$egur4".toCharArray();

            byte[] encrypted = CertificateEncryptor.encryptPassword(password, key);
            char[] decrypted = CertificateEncryptor.decryptPassword(encrypted, key);

            assertArrayEquals(password, decrypted);
        }

        @Test
        @DisplayName("round-trip con caracteres especiales y Unicode")
        void roundTripWithUnicodePassword() {
            byte[] key = masterKey();
            char[] password = "cöñtraseña€ñ∑∏".toCharArray();

            byte[] encrypted = CertificateEncryptor.encryptPassword(password, key);
            char[] decrypted = CertificateEncryptor.decryptPassword(encrypted, key);

            assertArrayEquals(password, decrypted);
        }

        @Test
        @DisplayName("clave incorrecta falla al descifrar contraseña")
        void wrongKeyFailsPasswordDecryption() {
            byte[] key1 = masterKey();
            byte[] key2 = masterKey();
            char[] password = "secreto".toCharArray();

            byte[] encrypted = CertificateEncryptor.encryptPassword(password, key1);

            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.decryptPassword(encrypted, key2));
        }

        @Test
        @DisplayName("contraseña nula lanza excepción")
        void nullPasswordThrows() {
            byte[] key = masterKey();
            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.encryptPassword(null, key));
        }

        @Test
        @DisplayName("contraseña vacía lanza excepción")
        void emptyPasswordThrows() {
            byte[] key = masterKey();
            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.encryptPassword(new char[0], key));
        }
    }

    // ── Input validation ──

    @Nested
    @DisplayName("validación de inputs")
    class InputValidation {

        @Test
        @DisplayName("encrypt con datos nulos lanza excepción")
        void encryptNullDataThrows() {
            byte[] key = masterKey();
            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.encrypt(null, key));
        }

        @Test
        @DisplayName("encrypt con datos vacíos lanza excepción")
        void encryptEmptyDataThrows() {
            byte[] key = masterKey();
            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.encrypt(new byte[0], key));
        }

        @Test
        @DisplayName("encrypt con clave nula lanza excepción")
        void encryptNullKeyThrows() {
            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.encrypt("data".getBytes(), null));
        }

        @Test
        @DisplayName("encrypt con clave de tamaño incorrecto lanza excepción")
        void encryptWrongKeySizeThrows() {
            byte[] shortKey = new byte[16]; // 128 bits, not 256
            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.encrypt("data".getBytes(), shortKey));
        }

        @Test
        @DisplayName("decrypt con datos nulos lanza excepción")
        void decryptNullDataThrows() {
            byte[] key = masterKey();
            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.decrypt(null, key));
        }

        @Test
        @DisplayName("decrypt con datos demasiado cortos lanza excepción")
        void decryptTooShortDataThrows() {
            byte[] key = masterKey();
            byte[] tooShort = new byte[12]; // Only IV, no ciphertext
            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.decrypt(tooShort, key));
        }

        @Test
        @DisplayName("decrypt con clave nula lanza excepción")
        void decryptNullKeyThrows() {
            byte[] key = masterKey();
            byte[] encrypted = CertificateEncryptor.encrypt("test".getBytes(), key);
            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.decrypt(encrypted, null));
        }
    }

    // ── Master key utilities ──

    @Nested
    @DisplayName("utilidades de clave maestra")
    class MasterKeyUtilities {

        @Test
        @DisplayName("generateMasterKey genera exactamente 32 bytes")
        void generateMasterKeyReturns32Bytes() {
            byte[] key = CertificateEncryptor.generateMasterKey();
            assertEquals(32, key.length);
        }

        @Test
        @DisplayName("generateMasterKey genera claves diferentes cada vez")
        void generateMasterKeyProducesUniqueKeys() {
            byte[] key1 = CertificateEncryptor.generateMasterKey();
            byte[] key2 = CertificateEncryptor.generateMasterKey();
            assertFalse(Arrays.equals(key1, key2));
        }

        @Test
        @DisplayName("decodeMasterKey decodifica Base64 correctamente")
        void decodeMasterKeyFromBase64() {
            byte[] original = CertificateEncryptor.generateMasterKey();
            String base64 = Base64.getEncoder().encodeToString(original);

            byte[] decoded = CertificateEncryptor.decodeMasterKey(base64);

            assertArrayEquals(original, decoded);
        }

        @Test
        @DisplayName("decodeMasterKey con Base64 inválido lanza excepción")
        void decodeMasterKeyInvalidBase64Throws() {
            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.decodeMasterKey("not-valid-base64!!!"));
        }

        @Test
        @DisplayName("decodeMasterKey con clave de tamaño incorrecto lanza excepción")
        void decodeMasterKeyWrongSizeThrows() {
            String shortKeyBase64 = Base64.getEncoder().encodeToString(new byte[16]);
            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.decodeMasterKey(shortKeyBase64));
        }

        @Test
        @DisplayName("decodeMasterKey con null lanza excepción")
        void decodeMasterKeyNullThrows() {
            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.decodeMasterKey(null));
        }

        @Test
        @DisplayName("decodeMasterKey con cadena vacía lanza excepción")
        void decodeMasterKeyBlankThrows() {
            assertThrows(EncryptionException.class,
                    () -> CertificateEncryptor.decodeMasterKey("   "));
        }
    }

    // ── Round-trip integration: encrypt → decrypt → sign ──

    @Nested
    @DisplayName("round-trip cifrar → descifrar → firmar")
    class RoundTripSignIntegration {

        @Test
        @DisplayName("cifrar .p12 y contraseña → descifrar → firmar exitosamente")
        void encryptDecryptThenSignSucceeds() {
            byte[] key = masterKey();
            byte[] p12Original = loadTestCertificate();
            char[] passwordOriginal = TEST_PASSWORD.clone();

            // Encrypt
            byte[] encryptedP12 = CertificateEncryptor.encrypt(p12Original, key);
            byte[] encryptedPassword = CertificateEncryptor.encryptPassword(passwordOriginal, key);

            // Decrypt
            byte[] decryptedP12 = CertificateEncryptor.decrypt(encryptedP12, key);
            char[] decryptedPassword = CertificateEncryptor.decryptPassword(encryptedPassword, key);

            // Sign — validates the decrypted cert and password are usable
            String signedXml = XAdESBESSigner.sign(simpleInvoiceXml(), decryptedP12, decryptedPassword);

            assertNotNull(signedXml);
            assertTrue(signedXml.contains("ds:Signature") || signedXml.contains("Signature"),
                    "Signed XML should contain Signature element");
        }

        @Test
        @DisplayName("cifrar con clave Base64 del entorno → descifrar → firmar")
        void base64MasterKeyRoundTrip() {
            byte[] rawKey = CertificateEncryptor.generateMasterKey();
            String base64Key = Base64.getEncoder().encodeToString(rawKey);

            // Simulate reading from environment variable
            byte[] key = CertificateEncryptor.decodeMasterKey(base64Key);

            byte[] p12 = loadTestCertificate();
            byte[] encryptedP12 = CertificateEncryptor.encrypt(p12, key);
            byte[] encryptedPwd = CertificateEncryptor.encryptPassword(TEST_PASSWORD.clone(), key);

            byte[] decryptedP12 = CertificateEncryptor.decrypt(encryptedP12, key);
            char[] decryptedPwd = CertificateEncryptor.decryptPassword(encryptedPwd, key);

            String signedXml = XAdESBESSigner.sign(simpleInvoiceXml(), decryptedP12, decryptedPwd);

            assertNotNull(signedXml);
            assertTrue(signedXml.contains("ds:Signature") || signedXml.contains("Signature"),
                    "Signed XML should contain Signature element");
        }
    }
}
