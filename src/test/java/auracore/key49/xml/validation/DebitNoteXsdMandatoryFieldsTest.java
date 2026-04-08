package auracore.key49.xml.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import auracore.key49.core.model.enums.DocumentType;

/**
 * Tests negativos que verifican que el XSD nota de débito v1.0.0 del SRI
 * rechaza el XML cuando falta un campo obligatorio.
 */
class DebitNoteXsdMandatoryFieldsTest {

    private static final DocumentType TYPE = DocumentType.DEBIT_NOTE;

    // ── Sanity check ────────────────────────────────────────────────────────
    @Test
    @DisplayName("XML base generado por builder pasa validación XSD")
    void baseXmlIsValid() {
        assertDoesNotThrow(() -> XmlTestHelper.validateAgainstXsd(
                XmlTestHelper.buildValidXml(TYPE), TYPE));
    }

    // ── infoTributaria ──────────────────────────────────────────────────────
    @Nested
    @DisplayName("Campos obligatorios faltantes en infoTributaria")
    class InfoTributaria {

        @ParameterizedTest(name = "sin <{0}> falla XSD")
        @ValueSource(strings = {
            "ambiente", "tipoEmision", "razonSocial", "ruc", "claveAcceso",
            "codDoc", "estab", "ptoEmi", "secuencial", "dirMatriz"
        })
        @DisplayName("remover campo obligatorio de infoTributaria causa fallo XSD")
        void missingMandatoryField(String field) throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "infoTributaria", field);
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }
    }

    // ── infoNotaDebito ──────────────────────────────────────────────────────
    @Nested
    @DisplayName("Campos obligatorios faltantes en infoNotaDebito")
    class InfoNotaDebito {

        @ParameterizedTest(name = "sin <{0}> falla XSD")
        @ValueSource(strings = {
            "fechaEmision", "tipoIdentificacionComprador", "razonSocialComprador",
            "identificacionComprador", "codDocModificado", "numDocModificado",
            "fechaEmisionDocSustento", "totalSinImpuestos", "impuestos", "valorTotal"
        })
        @DisplayName("remover campo obligatorio de infoNotaDebito causa fallo XSD")
        void missingMandatoryField(String field) throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "infoNotaDebito", field);
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }
    }

    // ── motivos ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Campos obligatorios faltantes en motivos")
    class Motivos {

        @Test
        @DisplayName("nota de débito sin nodo <motivos> falla XSD")
        void missingMotivosNode() throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "notaDebito", "motivos");
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }

        @ParameterizedTest(name = "sin <{0}> en motivo falla XSD")
        @ValueSource(strings = {"razon", "valor"})
        @DisplayName("remover campo obligatorio de motivo causa fallo XSD")
        void missingMandatoryFieldInMotivo(String field) throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "motivo", field);
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }
    }
}
