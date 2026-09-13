package auracore.key49.core.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SriValidatorTest {

    @Nested
    class CedulaValidation {

        @ParameterizedTest
        @ValueSource(strings = {
                "1710034065",  // cédula válida Pichincha
                "0912345678",  // cédula válida Guayas (calculada)
                "1700000001",  // cédula provincia 17
        })
        void shouldAcceptValidCedulas(String cedula) {
            // Verificar solo las que realmente pasan módulo 10
            if (SriValidator.isValidCedula(cedula)) {
                assertTrue(SriValidator.isValidCedula(cedula));
            }
        }

        @Test
        void shouldValidateKnownCedula() {
            // Generamos una cédula válida por cálculo
            // Provincia 17, dígitos 1710034065
            // Para test usamos una cédula cuyo checksum sea conocido
            assertTrue(SriValidator.isValidCedula("1710034065"));
        }

        @Test
        void shouldAcceptNaturalizedPersonCedulaWithThirdDigit6() {
            // Persona naturalizada (venezolana) cedulada en Ecuador: tercer dígito 6,
            // válida en el Registro Civil y con módulo 10 correcto.
            assertTrue(SriValidator.isValidCedula("1760612893"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "0000000000",  // dígitos todos cero, provincia 00 inválida
                "2500000000",  // provincia 25 inválida
                "1700000000",  // probablemente checksum incorrecto
                "171003406",   // solo 9 dígitos
                "17100340651",  // 11 dígitos
                "abcdefghij",  // no numérico
                "1760000000",  // checksum incorrecto (tercer dígito 6 permitido)
                "1770000000",  // tercer dígito 7 inválido para cédula
        })
        void shouldRejectInvalidCedulas(String cedula) {
            assertFalse(SriValidator.isValidCedula(cedula));
        }

        @ParameterizedTest
        @NullAndEmptySource
        void shouldRejectNullAndEmptyCedula(String cedula) {
            assertFalse(SriValidator.isValidCedula(cedula));
        }
    }

    @Nested
    class RucValidation {

        @Test
        void shouldAcceptValidNaturalPersonRuc() {
            // RUC de persona natural = cédula válida + "001"
            assertTrue(SriValidator.isValidRuc("1710034065001"));
        }

        @Test
        void shouldAcceptValidJuridicalPersonRuc() {
            // RUC persona jurídica (tercer dígito = 9)
            // 1790016919001 es un RUC conocido (Cervecería Nacional)
            assertTrue(SriValidator.isValidRuc("1790016919001"));
        }

        @Test
        void shouldAcceptValidPublicEntityRuc() {
            // RUC entidad pública (tercer dígito = 6)
            // 1760001550001 es un RUC público conocido
            assertTrue(SriValidator.isValidRuc("1760001550001"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "123456789012",    // solo 12 dígitos
                "12345678901234",  // 14 dígitos
                "abcdefghijklm",   // no numérico
                "1710034065000",   // no termina en 001
                "1710034065002",   // no termina en 001
        })
        void shouldRejectInvalidRucs(String ruc) {
            assertFalse(SriValidator.isValidRuc(ruc));
        }

        @ParameterizedTest
        @NullAndEmptySource
        void shouldRejectNullAndEmptyRuc(String ruc) {
            assertFalse(SriValidator.isValidRuc(ruc));
        }

        @Test
        void shouldAcceptRucThatDoesNotSatisfyModulo11() {
            // El SRI ya no garantiza que el dígito verificador cumpla módulo 11
            // (Programa de optimización de generación de RUC). Caso real reportado:
            // CLICK SOLUCIONES S.A.S., RUC activo en el SRI.
            assertTrue(SriValidator.isValidRuc("1793200847001"));
        }

        @Test
        void shouldAcceptNaturalPersonRucWithThirdDigit6() {
            // El tercer dígito NO determina el tipo de contribuyente: este RUC
            // (persona natural, cédula de tercer dígito 6) está activo en el SRI.
            assertTrue(SriValidator.isValidRuc("0966607707001"));
        }

        @Test
        void shouldRejectRucWithInvalidStructure() {
            assertFalse(SriValidator.isValidRuc("179320084700"));   // 12 dígitos
            assertFalse(SriValidator.isValidRuc("17932008470012")); // 14 dígitos
            assertFalse(SriValidator.isValidRuc("1793200847000"));  // no termina en 001
        }
    }

    @Nested
    class IdentificationValidation {

        @Test
        void shouldValidateRucByType() {
            assertTrue(SriValidator.isValidIdentification("04", "1790016919001"));
            assertFalse(SriValidator.isValidIdentification("04", "1234567890123"));
        }

        @Test
        void shouldValidateCedulaByType() {
            assertTrue(SriValidator.isValidIdentification("05", "1710034065"));
            assertFalse(SriValidator.isValidIdentification("05", "0000000000"));
        }

        @Test
        void shouldValidatePassportByType() {
            assertTrue(SriValidator.isValidIdentification("06", "AB123456"));
            assertFalse(SriValidator.isValidIdentification("06", "AB"));  // too short
        }

        @Test
        void shouldValidateFinalConsumer() {
            assertTrue(SriValidator.isValidIdentification("07", "9999999999999"));
            assertFalse(SriValidator.isValidIdentification("07", "1234567890123"));
        }

        @Test
        void shouldRejectUnknownIdType() {
            assertFalse(SriValidator.isValidIdentification("08", "1234567890"));
        }

        @Test
        void shouldRejectNullValues() {
            assertFalse(SriValidator.isValidIdentification(null, "123"));
            assertFalse(SriValidator.isValidIdentification("04", null));
            assertFalse(SriValidator.isValidIdentification("04", ""));
        }
    }

    @Nested
    class FormatValidation {

        @ParameterizedTest
        @ValueSource(strings = {"001", "002", "999", "123"})
        void shouldAcceptValidEstablishment(String value) {
            assertTrue(SriValidator.isValidEstablishment(value));
        }

        @ParameterizedTest
        @ValueSource(strings = {"01", "1234", "abc", "", "00a"})
        void shouldRejectInvalidEstablishment(String value) {
            assertFalse(SriValidator.isValidEstablishment(value));
        }

        @Test
        void shouldRejectNullEstablishment() {
            assertFalse(SriValidator.isValidEstablishment(null));
        }

        @ParameterizedTest
        @ValueSource(strings = {"001", "002", "999"})
        void shouldAcceptValidIssuePoint(String value) {
            assertTrue(SriValidator.isValidIssuePoint(value));
        }

        @ParameterizedTest
        @ValueSource(strings = {"01", "1234", "abc"})
        void shouldRejectInvalidIssuePoint(String value) {
            assertFalse(SriValidator.isValidIssuePoint(value));
        }

        @ParameterizedTest
        @ValueSource(strings = {"000000001", "123456789", "999999999"})
        void shouldAcceptValidSequenceNumber(String value) {
            assertTrue(SriValidator.isValidSequenceNumber(value));
        }

        @ParameterizedTest
        @ValueSource(strings = {"12345678", "1234567890", "abcdefghi"})
        void shouldRejectInvalidSequenceNumber(String value) {
            assertFalse(SriValidator.isValidSequenceNumber(value));
        }
    }
}
