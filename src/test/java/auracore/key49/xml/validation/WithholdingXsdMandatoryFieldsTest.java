package auracore.key49.xml.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import auracore.key49.core.model.enums.DocumentType;

/**
 * Tests negativos que verifican que el XSD comprobante de retención v2.0.0 del
 * SRI rechaza el XML cuando falta un campo obligatorio.
 */
class WithholdingXsdMandatoryFieldsTest {

    private static final DocumentType TYPE = DocumentType.WITHHOLDING;

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

    // ── infoCompRetencion ───────────────────────────────────────────────────
    @Nested
    @DisplayName("Campos obligatorios faltantes en infoCompRetencion")
    class InfoCompRetencion {

        @ParameterizedTest(name = "sin <{0}> falla XSD")
        @ValueSource(strings = {
            "fechaEmision", "tipoIdentificacionSujetoRetenido",
            "parteRel", "razonSocialSujetoRetenido",
            "identificacionSujetoRetenido", "periodoFiscal"
        })
        @DisplayName("remover campo obligatorio de infoCompRetencion causa fallo XSD")
        void missingMandatoryField(String field) throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "infoCompRetencion", field);
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }
    }

    // ── docsSustento ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Campos obligatorios faltantes en docsSustento")
    class DocsSustento {

        @Test
        @DisplayName("retención sin nodo <docsSustento> falla XSD")
        void missingDocsSustentoNode() throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "comprobanteRetencion", "docsSustento");
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }

        @ParameterizedTest(name = "sin <{0}> en docSustento falla XSD")
        @ValueSource(strings = {
            "codSustento", "codDocSustento", "numDocSustento",
            "fechaEmisionDocSustento", "pagoLocExt",
            "totalSinImpuestos", "importeTotal",
            "impuestosDocSustento", "retenciones", "pagos"
        })
        @DisplayName("remover campo obligatorio de docSustento causa fallo XSD")
        void missingMandatoryFieldInDocSustento(String field) throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "docSustento", field);
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }
    }

    // ── retencion ───────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Campos obligatorios faltantes en retencion")
    class Retencion {

        @ParameterizedTest(name = "sin <{0}> en retencion falla XSD")
        @ValueSource(strings = {
            "codigo", "codigoRetencion", "baseImponible",
            "porcentajeRetener", "valorRetenido"
        })
        @DisplayName("remover campo obligatorio de retencion causa fallo XSD")
        void missingMandatoryFieldInRetencion(String field) throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "retencion", field);
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }
    }
}
