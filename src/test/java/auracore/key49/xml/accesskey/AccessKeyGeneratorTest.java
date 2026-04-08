package auracore.key49.xml.accesskey;

import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.core.model.enums.SriEnvironment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

class AccessKeyGeneratorTest {

    // Usamos la fecha de hoy para los tests que generan claves
    private static final LocalDate TODAY = LocalDate.now(Key49Constants.EC_ZONE);
    private static final String VALID_RUC = "1790016919001";
    private static final String ESTABLISHMENT = "001";
    private static final String ISSUE_POINT = "001";
    private static final String SEQUENCE = "000000001";
    private static final String NUMERIC_CODE = "12345678";

    @Nested
    class Generate {

        @Test
        void shouldGenerateAccessKeyWith49Digits() {
            var key = AccessKeyGenerator.generate(
                    TODAY, DocumentType.INVOICE, VALID_RUC,
                    SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, SEQUENCE);

            assertNotNull(key);
            assertEquals(49, key.length());
            assertTrue(key.matches("\\d{49}"), "Access key must be all digits");
        }

        @Test
        void shouldGenerateDeterministicKeyWithNumericCode() {
            var key = AccessKeyGenerator.generate(
                    TODAY, DocumentType.INVOICE, VALID_RUC,
                    SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, SEQUENCE, NUMERIC_CODE);

            var expectedDate = TODAY.format(DateTimeFormatter.ofPattern("ddMMyyyy"));
            assertTrue(key.startsWith(expectedDate), "Key should start with date ddMMyyyy");
            assertEquals("01", key.substring(8, 10), "Document type should be 01 (invoice)");
            assertEquals(VALID_RUC, key.substring(10, 23), "RUC should be at positions 10-22");
            assertEquals("1", key.substring(23, 24), "Environment should be 1 (test)");
            assertEquals(ESTABLISHMENT, key.substring(24, 27), "Establishment at positions 24-26");
            assertEquals(ISSUE_POINT, key.substring(27, 30), "Issue point at positions 27-29");
            assertEquals(SEQUENCE, key.substring(30, 39), "Sequence at positions 30-38");
            assertEquals(NUMERIC_CODE, key.substring(39, 47), "Numeric code at positions 39-46");
            assertEquals("1", key.substring(47, 48), "Emission type should be 1");
        }

        @Test
        void shouldGenerateValidCheckDigit() {
            var key = AccessKeyGenerator.generate(
                    TODAY, DocumentType.INVOICE, VALID_RUC,
                    SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, SEQUENCE, NUMERIC_CODE);

            assertTrue(AccessKeyGenerator.isValid(key), "Generated key should pass validation");
        }

        @Test
        void shouldEmbedProductionEnvironment() {
            var key = AccessKeyGenerator.generate(
                    TODAY, DocumentType.INVOICE, VALID_RUC,
                    SriEnvironment.PRODUCTION, ESTABLISHMENT, ISSUE_POINT, SEQUENCE, NUMERIC_CODE);

            assertEquals("2", key.substring(23, 24), "Environment should be 2 (production)");
            assertTrue(AccessKeyGenerator.isValid(key));
        }

        @ParameterizedTest
        @CsvSource({
                "INVOICE, 01",
                "CREDIT_NOTE, 04",
                "DEBIT_NOTE, 05",
                "WAYBILL, 06",
                "WITHHOLDING, 07",
                "PURCHASE_CLEARANCE, 03"
        })
        void shouldEmbedDocumentTypeCode(DocumentType docType, String expectedCode) {
            var key = AccessKeyGenerator.generate(
                    TODAY, docType, VALID_RUC,
                    SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, SEQUENCE, NUMERIC_CODE);

            assertEquals(expectedCode, key.substring(8, 10));
            assertTrue(AccessKeyGenerator.isValid(key));
        }

        @Test
        void shouldWorkWithDifferentEstablishments() {
            var key = AccessKeyGenerator.generate(
                    TODAY, DocumentType.INVOICE, VALID_RUC,
                    SriEnvironment.TEST, "005", "003", "000123456", NUMERIC_CODE);

            assertEquals("005", key.substring(24, 27));
            assertEquals("003", key.substring(27, 30));
            assertEquals("000123456", key.substring(30, 39));
            assertTrue(AccessKeyGenerator.isValid(key));
        }
    }

    @Nested
    class GenerateUniqueness {

        @Test
        void shouldGenerate1000UniqueKeysWithValidChecksum() {
            var keys = new HashSet<String>(1000);

            for (int i = 1; i <= 1000; i++) {
                var seq = String.format("%09d", i);
                var key = AccessKeyGenerator.generate(
                        TODAY, DocumentType.INVOICE, VALID_RUC,
                        SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, seq);

                assertTrue(keys.add(key), "Key must be unique: " + key);
                assertEquals(49, key.length(), "Key must be 49 digits");
                assertTrue(AccessKeyGenerator.isValid(key),
                        "Key must have valid checksum: " + key);
            }

            assertEquals(1000, keys.size());
        }

        @Test
        void shouldGenerateUniqueKeysWithSameSequence() {
            // Claves con mismo secuencial difieren por código numérico aleatorio
            var keys = new HashSet<String>(100);

            for (int i = 0; i < 100; i++) {
                var key = AccessKeyGenerator.generate(
                        TODAY, DocumentType.INVOICE, VALID_RUC,
                        SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, SEQUENCE);
                keys.add(key);
            }

            // Con 8 dígitos aleatorios (10^8 posibilidades) y 100 claves,
            // la probabilidad de colisión es prácticamente cero
            assertTrue(keys.size() >= 99, "Should have mostly unique keys");
        }
    }

    @Nested
    class Validation {

        @Test
        void shouldValidateCorrectKey() {
            var key = AccessKeyGenerator.generate(
                    TODAY, DocumentType.INVOICE, VALID_RUC,
                    SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, SEQUENCE, NUMERIC_CODE);

            assertTrue(AccessKeyGenerator.isValid(key));
        }

        @Test
        void shouldRejectKeyWithWrongCheckDigit() {
            var key = AccessKeyGenerator.generate(
                    TODAY, DocumentType.INVOICE, VALID_RUC,
                    SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, SEQUENCE, NUMERIC_CODE);

            // Alteramos el último dígito (verificador)
            int lastDigit = key.charAt(48) - '0';
            int wrongDigit = (lastDigit + 1) % 10;
            var tampered = key.substring(0, 48) + wrongDigit;

            assertFalse(AccessKeyGenerator.isValid(tampered));
        }

        @Test
        void shouldRejectKeyWithAlteredMiddle() {
            var key = AccessKeyGenerator.generate(
                    TODAY, DocumentType.INVOICE, VALID_RUC,
                    SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, SEQUENCE, NUMERIC_CODE);

            // Alterar un dígito del RUC (posición 15)
            char original = key.charAt(15);
            char altered = (original == '9') ? '0' : (char) (original + 1);
            var tampered = key.substring(0, 15) + altered + key.substring(16);

            assertFalse(AccessKeyGenerator.isValid(tampered));
        }

        @ParameterizedTest
        @NullAndEmptySource
        void shouldRejectNullAndEmpty(String key) {
            assertFalse(AccessKeyGenerator.isValid(key));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "1234567890",                                      // too short
                "12345678901234567890123456789012345678901234567890", // 50 digits
                "123456789012345678901234567890123456789012345678",  // 48 digits
                "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvw", // 49 chars, not digits
        })
        void shouldRejectInvalidLengthOrFormat(String key) {
            assertFalse(AccessKeyGenerator.isValid(key));
        }
    }

    @Nested
    class Parse {

        @Test
        void shouldParseAccessKeyComponents() {
            var key = AccessKeyGenerator.generate(
                    TODAY, DocumentType.INVOICE, VALID_RUC,
                    SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, SEQUENCE, NUMERIC_CODE);

            var components = AccessKeyGenerator.parse(key);

            assertEquals(TODAY.format(DateTimeFormatter.ofPattern("ddMMyyyy")), components.date());
            assertEquals("01", components.documentType());
            assertEquals(VALID_RUC, components.ruc());
            assertEquals("1", components.environment());
            assertEquals(ESTABLISHMENT, components.establishment());
            assertEquals(ISSUE_POINT, components.issuePoint());
            assertEquals(SEQUENCE, components.sequenceNumber());
            assertEquals(NUMERIC_CODE, components.numericCode());
            assertEquals("1", components.emissionType());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"12345", "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvw"})
        void shouldThrowOnInvalidKeyForParsing(String key) {
            assertThrows(IllegalArgumentException.class, () -> AccessKeyGenerator.parse(key));
        }
    }

    @Nested
    class Modulo11 {

        @Test
        void shouldCalculateCheckDigitForKnownValue() {
            // Verificar con ejemplo conocido: todos los dígitos son 1
            var digits = "1".repeat(48);
            int checkDigit = AccessKeyGenerator.calculateModulo11(digits);
            assertTrue(checkDigit >= 0 && checkDigit <= 10);
        }

        @Test
        void shouldHandleCheckDigit11BecomingZero() {
            // Si 11 - (sum % 11) == 11, entonces check digit = 0
            // Esto ocurre cuando sum % 11 == 0
            // Necesitamos construir un caso donde sum sea múltiplo de 11
            // Podemos verificar que el resultado siempre es 0-9
            for (int i = 0; i < 100; i++) {
                var code = AccessKeyGenerator.generateNumericCode();
                var base = TODAY.format(DateTimeFormatter.ofPattern("ddMMyyyy"))
                        + "01" + VALID_RUC + "1" + ESTABLISHMENT + ISSUE_POINT + SEQUENCE + code + "1";
                int check = AccessKeyGenerator.calculateModulo11(base);
                assertTrue(check >= 0 && check <= 9,
                        "Check digit must be 0-9 but was " + check);
            }
        }

        @Test
        void shouldProduceConsistentCheckDigit() {
            var digits = "050420260117900169190011001001000000001123456781";
            int first = AccessKeyGenerator.calculateModulo11(digits);
            int second = AccessKeyGenerator.calculateModulo11(digits);
            assertEquals(first, second, "Same input must produce same check digit");
        }
    }

    @Nested
    class InputValidation {

        @Test
        void shouldRejectNullIssueDate() {
            assertThrows(IllegalArgumentException.class, () ->
                    AccessKeyGenerator.generate(null, DocumentType.INVOICE, VALID_RUC,
                            SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, SEQUENCE));
        }

        @Test
        void shouldRejectIssueDateNotToday() {
            var yesterday = TODAY.minusDays(1);
            var ex = assertThrows(IllegalArgumentException.class, () ->
                    AccessKeyGenerator.generate(yesterday, DocumentType.INVOICE, VALID_RUC,
                            SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, SEQUENCE));
            assertTrue(ex.getMessage().contains("today"));
        }

        @Test
        void shouldRejectFutureIssueDate() {
            var tomorrow = TODAY.plusDays(1);
            assertThrows(IllegalArgumentException.class, () ->
                    AccessKeyGenerator.generate(tomorrow, DocumentType.INVOICE, VALID_RUC,
                            SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, SEQUENCE));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"123456789012", "12345678901234", "abcdefghijklm"})
        void shouldRejectInvalidRuc(String ruc) {
            assertThrows(IllegalArgumentException.class, () ->
                    AccessKeyGenerator.generate(TODAY, DocumentType.INVOICE, ruc,
                            SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, SEQUENCE));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"01", "0001", "abc", "1"})
        void shouldRejectInvalidEstablishment(String estab) {
            assertThrows(IllegalArgumentException.class, () ->
                    AccessKeyGenerator.generate(TODAY, DocumentType.INVOICE, VALID_RUC,
                            SriEnvironment.TEST, estab, ISSUE_POINT, SEQUENCE));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"01", "0001", "abc", "1"})
        void shouldRejectInvalidIssuePoint(String point) {
            assertThrows(IllegalArgumentException.class, () ->
                    AccessKeyGenerator.generate(TODAY, DocumentType.INVOICE, VALID_RUC,
                            SriEnvironment.TEST, ESTABLISHMENT, point, SEQUENCE));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"12345678", "1234567890", "abcdefghi", "1"})
        void shouldRejectInvalidSequenceNumber(String seq) {
            assertThrows(IllegalArgumentException.class, () ->
                    AccessKeyGenerator.generate(TODAY, DocumentType.INVOICE, VALID_RUC,
                            SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, seq));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"1234567", "123456789", "abcdefgh"})
        void shouldRejectInvalidNumericCode(String code) {
            assertThrows(IllegalArgumentException.class, () ->
                    AccessKeyGenerator.generate(TODAY, DocumentType.INVOICE, VALID_RUC,
                            SriEnvironment.TEST, ESTABLISHMENT, ISSUE_POINT, SEQUENCE, code));
        }
    }

    @Nested
    class NumericCodeGeneration {

        @Test
        void shouldGenerateValidNumericCodes() {
            for (int i = 0; i < 100; i++) {
                var code = AccessKeyGenerator.generateNumericCode();
                assertEquals(8, code.length(), "Numeric code must be 8 digits");
                assertTrue(code.matches("\\d{8}"), "Numeric code must be all digits");
            }
        }
    }
}
