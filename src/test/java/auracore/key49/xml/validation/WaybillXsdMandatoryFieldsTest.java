package auracore.key49.xml.validation;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import auracore.key49.xml.builder.WaybillData;
import auracore.key49.xml.builder.WaybillXmlBuilder;

/**
 * Tests negativos que verifican que el XSD guía de remisión v1.1.0 del SRI
 * rechaza el XML cuando falta un campo obligatorio.
 */
class WaybillXsdMandatoryFieldsTest {

    // ── Helpers ──────────────────────────────────────────────────────────────
    private String buildValidWaybillXml() {
        var taxpayer = new WaybillData.TaxpayerInfo(
                "1", "1",
                "EMPRESA DE PRUEBAS S.A.", "PRUEBAS COMERCIAL",
                "1792146739001",
                "Quito, Av. Amazonas N24-345",
                "Quito, Sucursal Norte", true,
                "12345", "1", "CONTRIBUYENTE RÉGIMEN RIMPE");
        var carrier = new WaybillData.Carrier(
                "04", "1790016919001",
                "TRANSPORTES DEL NORTE CIA. LTDA.", null);
        var item = new WaybillData.Item(
                "PROD001", "AUX001", "Producto de prueba A",
                new BigDecimal("100.000000"),
                List.of(new WaybillData.ItemDetail("Lote", "L-2025-001")));
        var addressee = new WaybillData.Addressee(
                "1790016919001", "CLIENTE NACIONAL CIA. LTDA.",
                "Guayaquil, Av. 9 de Octubre 100",
                "Venta de mercadería",
                null, "002", "Quito-Guayaquil",
                "01", "001-001-000000234",
                "1503202501179214673900110010010000002340000002341",
                LocalDate.of(2025, 3, 15),
                List.of(item));

        return WaybillXmlBuilder.build(new WaybillData(
                taxpayer,
                "1504202506179214673900110010010000001230000001231",
                "001", "001", "000000123",
                LocalDate.of(2025, 4, 15),
                "Quito, Bodega Central Km 10",
                carrier,
                LocalDate.of(2025, 4, 15),
                LocalDate.of(2025, 4, 16),
                "PBB-1234",
                List.of(addressee),
                Map.of("Email", "transportes@test.com")));
    }

    private Document parseXml(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        var builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private String serialize(Document doc) throws Exception {
        var tf = TransformerFactory.newInstance();
        var transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        var sw = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    private String removeElement(String xml, String parentTag, String childTag) throws Exception {
        var doc = parseXml(xml);
        var parents = doc.getElementsByTagName(parentTag);
        assertTrue(parents.getLength() > 0,
                "Parent <%s> must exist in the XML".formatted(parentTag));
        var parent = (Element) parents.item(0);
        var children = parent.getElementsByTagName(childTag);
        assertTrue(children.getLength() > 0,
                "Child <%s> must exist in <%s> before removal".formatted(childTag, parentTag));
        var child = children.item(0);
        child.getParentNode().removeChild(child);
        return serialize(doc);
    }

    private void assertXsdFails(String xml) {
        assertThrows(SAXException.class, () -> validateAgainstXsd(xml),
                "XSD validation should fail for XML with missing mandatory field");
    }

    private void validateAgainstXsd(String xml) throws SAXException, IOException {
        var schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        var xsdUrl = getClass().getResource("/xsd/sri/GuiaRemision_V1.1.0.xsd");
        assertNotNull(xsdUrl, "XSD file must be on classpath");
        var schema = schemaFactory.newSchema(xsdUrl);
        var validator = schema.newValidator();
        validator.validate(new StreamSource(new StringReader(xml)));
    }

    // ── Sanity check ────────────────────────────────────────────────────────
    @Test
    @DisplayName("XML base generado por builder pasa validación XSD")
    void baseXmlIsValid() {
        assertDoesNotThrow(() -> validateAgainstXsd(buildValidWaybillXml()));
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
            var xml = removeElement(buildValidWaybillXml(), "infoTributaria", field);
            assertXsdFails(xml);
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
            var xml = removeElement(buildValidWaybillXml(), "infoGuiaRemision", field);
            assertXsdFails(xml);
        }
    }

    // ── destinatario ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Campos obligatorios faltantes en destinatario")
    class Destinatario {

        @Test
        @DisplayName("guía de remisión sin nodo <destinatarios> falla XSD")
        void missingDestinatariosNode() throws Exception {
            var xml = removeElement(buildValidWaybillXml(), "guiaRemision", "destinatarios");
            assertXsdFails(xml);
        }

        @ParameterizedTest(name = "sin <{0}> en destinatario falla XSD")
        @ValueSource(strings = {
            "identificacionDestinatario", "razonSocialDestinatario",
            "dirDestinatario", "motivoTraslado"
        })
        @DisplayName("remover campo obligatorio de destinatario causa fallo XSD")
        void missingMandatoryFieldInDestinatario(String field) throws Exception {
            var xml = removeElement(buildValidWaybillXml(), "destinatario", field);
            assertXsdFails(xml);
        }

        @Test
        @DisplayName("destinatario sin nodo <detalles> falla XSD")
        void missingDetallesNode() throws Exception {
            var xml = removeElement(buildValidWaybillXml(), "destinatario", "detalles");
            assertXsdFails(xml);
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
            var xml = removeElement(buildValidWaybillXml(), "detalle", field);
            assertXsdFails(xml);
        }
    }
}
