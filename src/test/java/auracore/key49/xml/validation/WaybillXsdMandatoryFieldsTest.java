package auracore.key49.xml.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import auracore.key49.core.model.enums.DocumentType;

/**
 * Tests negativos que verifican que el XSD guía de remisión v1.1.0 del SRI
 * rechaza el XML cuando falta un campo obligatorio.
 */
class WaybillXsdMandatoryFieldsTest {

    private static final DocumentType TYPE = DocumentType.WAYBILL;

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

    // ── infoGuiaRemision ────────────────────────────────────────────────────
    @Nested
    @DisplayName("Campos obligatorios faltantes en infoGuiaRemision")
    class InfoGuiaRemision {

        @ParameterizedTest(name = "sin <{0}> falla XSD")
        @ValueSource(strings = {
            "dirPartida", "razonSocialTransportista",
            "tipoIdentificacionTransportista", "rucTransportista",
            "fechaIniTransporte", "fechaFinTransporte", "placa"
        })
        @DisplayName("remover campo obligatorio de infoGuiaRemision causa fallo XSD")
        void missingMandatoryField(String field) throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "infoGuiaRemision", field);
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }
    }

    // ── destinatario ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Campos obligatorios faltantes en destinatario")
    class Destinatario {

        @Test
        @DisplayName("guía de remisión sin nodo <destinatarios> falla XSD")
        void missingDestinatariosNode() throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "guiaRemision", "destinatarios");
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }

        @ParameterizedTest(name = "sin <{0}> en destinatario falla XSD")
        @ValueSource(strings = {
            "identificacionDestinatario", "razonSocialDestinatario",
            "dirDestinatario", "motivoTraslado"
        })
        @DisplayName("remover campo obligatorio de destinatario causa fallo XSD")
        void missingMandatoryFieldInDestinatario(String field) throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "destinatario", field);
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }

        @Test
        @DisplayName("destinatario sin nodo <detalles> falla XSD")
        void missingDetallesNode() throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "destinatario", "detalles");
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }
    }

    // ── detalle ─────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Campos obligatorios faltantes en detalle")
    class Detalle {

        @ParameterizedTest(name = "sin <{0}> en detalle falla XSD")
        @ValueSource(strings = {
            "descripcion", "cantidad"
        })
        @DisplayName("remover campo obligatorio de detalle causa fallo XSD")
        void missingMandatoryFieldInDetalle(String field) throws Exception {
            var xml = XmlTestHelper.removeElement(
                    XmlTestHelper.buildValidXml(TYPE), "detalle", field);
            XmlTestHelper.assertXsdFails(xml, TYPE);
        }
    }
}
