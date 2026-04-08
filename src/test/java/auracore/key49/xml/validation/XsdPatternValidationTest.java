package auracore.key49.xml.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import auracore.key49.core.model.enums.DocumentType;

/**
 * Tests negativos que verifican que los XSD del SRI rechazan valores que no
 * cumplen los patterns (restricciones regex) definidos en infoTributaria. Se
 * prueban todos los tipos de comprobante para cada pattern.
 */
class XsdPatternValidationTest {

    // ── Sanity check ────────────────────────────────────────────────────────
    @ParameterizedTest(name = "{0}")
    @EnumSource(DocumentType.class)
    @DisplayName("XML base válido pasa XSD para cada tipo de comprobante")
    void baseXmlIsValid(DocumentType type) {
        assertDoesNotThrow(() -> XmlTestHelper.validateAgainstXsd(XmlTestHelper.buildValidXml(type), type));
    }

    // ── RUC inválido ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("RUC inválido rechazado por XSD")
    class RucInvalido {

        @ParameterizedTest(name = "{0}: RUC con letras")
        @EnumSource(DocumentType.class)
        @DisplayName("RUC con letras falla XSD")
        void rucWithLetters(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "ruc", "ABC0012345001");
            XmlTestHelper.assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: RUC demasiado corto")
        @EnumSource(DocumentType.class)
        @DisplayName("RUC con menos de 13 dígitos falla XSD")
        void rucTooShort(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "ruc", "179001234");
            XmlTestHelper.assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: RUC sin sufijo 001")
        @EnumSource(DocumentType.class)
        @DisplayName("RUC sin sufijo 001 falla XSD")
        void rucWithoutSuffix001(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "ruc", "1790012345999");
            XmlTestHelper.assertXsdFails(xml, type);
        }
    }

    // ── Establecimiento inválido ────────────────────────────────────────────
    @Nested
    @DisplayName("Establecimiento inválido rechazado por XSD")
    class EstablecimientoInvalido {

        @ParameterizedTest(name = "{0}: establecimiento con letras")
        @EnumSource(DocumentType.class)
        @DisplayName("Establecimiento con letras falla XSD")
        void estabWithLetters(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "estab", "A01");
            XmlTestHelper.assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: establecimiento con más de 3 dígitos")
        @EnumSource(DocumentType.class)
        @DisplayName("Establecimiento con más de 3 dígitos falla XSD")
        void estabTooLong(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "estab", "0012");
            XmlTestHelper.assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: establecimiento con menos de 3 dígitos")
        @EnumSource(DocumentType.class)
        @DisplayName("Establecimiento con menos de 3 dígitos falla XSD")
        void estabTooShort(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "estab", "01");
            XmlTestHelper.assertXsdFails(xml, type);
        }
    }

    // ── Punto de emisión inválido ───────────────────────────────────────────
    @Nested
    @DisplayName("Punto de emisión inválido rechazado por XSD")
    class PuntoEmisionInvalido {

        @ParameterizedTest(name = "{0}: ptoEmi con letras")
        @EnumSource(DocumentType.class)
        @DisplayName("Punto de emisión con letras falla XSD")
        void ptoEmiWithLetters(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "ptoEmi", "A01");
            XmlTestHelper.assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: ptoEmi con más de 3 dígitos")
        @EnumSource(DocumentType.class)
        @DisplayName("Punto de emisión con más de 3 dígitos falla XSD")
        void ptoEmiTooLong(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "ptoEmi", "0012");
            XmlTestHelper.assertXsdFails(xml, type);
        }
    }

    // ── Secuencial inválido ─────────────────────────────────────────────────
    @Nested
    @DisplayName("Secuencial inválido rechazado por XSD")
    class SecuencialInvalido {

        @ParameterizedTest(name = "{0}: secuencial con letras")
        @EnumSource(DocumentType.class)
        @DisplayName("Secuencial con letras falla XSD")
        void secuencialWithLetters(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "secuencial", "00000A042");
            XmlTestHelper.assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: secuencial demasiado corto")
        @EnumSource(DocumentType.class)
        @DisplayName("Secuencial con menos de 9 dígitos falla XSD")
        void secuencialTooShort(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "secuencial", "00042");
            XmlTestHelper.assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: secuencial demasiado largo")
        @EnumSource(DocumentType.class)
        @DisplayName("Secuencial con más de 9 dígitos falla XSD")
        void secuencialTooLong(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "secuencial", "0000000421");
            XmlTestHelper.assertXsdFails(xml, type);
        }
    }

    // ── Clave de acceso inválida ────────────────────────────────────────────
    @Nested
    @DisplayName("Clave de acceso inválida rechazada por XSD")
    class ClaveAccesoInvalida {

        @ParameterizedTest(name = "{0}: clave de acceso con letras")
        @EnumSource(DocumentType.class)
        @DisplayName("Clave de acceso con letras falla XSD")
        void claveAccesoWithLetters(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "claveAcceso",
                    "040420260117900123450011001001000000042123456781A");
            XmlTestHelper.assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: clave de acceso demasiado corta")
        @EnumSource(DocumentType.class)
        @DisplayName("Clave de acceso con menos de 49 dígitos falla XSD")
        void claveAccesoTooShort(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "claveAcceso",
                    "12345678901234567890");
            XmlTestHelper.assertXsdFails(xml, type);
        }
    }

    // ── Código de documento inválido ────────────────────────────────────────
    @Nested
    @DisplayName("Código de documento inválido rechazado por XSD")
    class CodDocInvalido {

        @ParameterizedTest(name = "{0}: codDoc con letras")
        @EnumSource(DocumentType.class)
        @DisplayName("Código de documento con letras falla XSD")
        void codDocWithLetters(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "codDoc", "AB");
            XmlTestHelper.assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: codDoc con más de 2 dígitos")
        @EnumSource(DocumentType.class)
        @DisplayName("Código de documento con más de 2 dígitos falla XSD")
        void codDocTooLong(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "codDoc", "012");
            XmlTestHelper.assertXsdFails(xml, type);
        }
    }

    // ── Ambiente inválido ───────────────────────────────────────────────────
    @Nested
    @DisplayName("Ambiente inválido rechazado por XSD")
    class AmbienteInvalido {

        @ParameterizedTest(name = "{0}: ambiente con valor 3")
        @EnumSource(DocumentType.class)
        @DisplayName("Ambiente con valor fuera de rango (3) falla XSD")
        void ambienteOutOfRange(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "ambiente", "3");
            XmlTestHelper.assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: ambiente con valor 0")
        @EnumSource(DocumentType.class)
        @DisplayName("Ambiente con valor 0 falla XSD")
        void ambienteZero(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "ambiente", "0");
            XmlTestHelper.assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: ambiente con letras")
        @EnumSource(DocumentType.class)
        @DisplayName("Ambiente con letras falla XSD")
        void ambienteWithLetters(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "ambiente", "A");
            XmlTestHelper.assertXsdFails(xml, type);
        }
    }

    // ── Tipo de emisión inválido ────────────────────────────────────────────
    @Nested
    @DisplayName("Tipo de emisión inválido rechazado por XSD")
    class TipoEmisionInvalido {

        @ParameterizedTest(name = "{0}: tipoEmision con valor 3")
        @EnumSource(DocumentType.class)
        @DisplayName("Tipo de emisión con valor fuera de rango (3) falla XSD")
        void tipoEmisionOutOfRange(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "tipoEmision", "3");
            XmlTestHelper.assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: tipoEmision con valor 0")
        @EnumSource(DocumentType.class)
        @DisplayName("Tipo de emisión con valor 0 falla XSD")
        void tipoEmisionZero(DocumentType type) throws Exception {
            var xml = XmlTestHelper.replaceElementValue(XmlTestHelper.buildValidXml(type), "tipoEmision", "0");
            XmlTestHelper.assertXsdFails(xml, type);
        }
    }
}
