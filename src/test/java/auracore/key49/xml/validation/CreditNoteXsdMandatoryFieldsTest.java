package auracore.key49.xml.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import auracore.key49.core.model.enums.DocumentType;

/**
 * Tests negativos que verifican que el XSD nota de crédito v1.1.0 del SRI
 * rechaza el XML cuando falta un campo obligatorio.
 */
class CreditNoteXsdMandatoryFieldsTest {

    private static final DocumentType TYPE = DocumentType.CREDIT_NOTE;

    // ── Sanity check ──
    @Test
    @DisplayName("XML base generado por builder pasa validación XSD")
    void baseXmlIsValid() {
        assertDoesNotThrow(() -> XmlTestHelper.validateAgainstXsd(
                XmlTestHelper.buildValidXml(TYPE), TYPE));
    }

    // ── infoTributaria ──
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

    // ── infoNotaCredito ──
    @Nested
    @DisplayName("Campos obligatorios faltantes en infoNotaCredito")
    class InfoNotaCredito {

        @ParameterizedTest(name = "sin <{0}> falla XSD")
        @ValueSource(strings = {
            "fechaEmision", "tipoIdentificacionComprador", "razonSocialComprador",
            "identificacionComprador", "codDocModificado", "numDocModificado",
            "fechaEmisionDocSustento", "totalSinImpuestos", "valorModificacion",
            "totalConImpuestos", "motivo"
        })
        @DisplayName("remover campo obligatorio de infoNotaCredito causa fallo XSD")
        void missingMandatoryField(String field) throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "infoNotaCredito", field);
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }
    }

    // ── detalles ──
    @Nested
    @DisplayName("Campos obligatorios faltantes en detalles")
    class Detalles {

        @Test
        @DisplayName("nota de crédito sin nodo <detalles> falla XSD")
        void missingDetallesNode() throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "notaCredito", "detalles");
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }

        @ParameterizedTest(name = "sin <{0}> en detalle falla XSD")
        @ValueSource(strings = {
            "descripcion", "cantidad", "precioUnitario",
            "precioTotalSinImpuesto", "impuestos"
        })
        @DisplayName("remover campo obligatorio de detalle causa fallo XSD")
        void missingMandatoryFieldInDetail(String field) throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "detalle", field);
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }
    }
}
