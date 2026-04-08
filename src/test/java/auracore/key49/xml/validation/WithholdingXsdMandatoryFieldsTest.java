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

import auracore.key49.xml.builder.WithholdingData;
import auracore.key49.xml.builder.WithholdingXmlBuilder;

/**
 * Tests negativos que verifican que el XSD comprobante de retención v2.0.0 del
 * SRI rechaza el XML cuando falta un campo obligatorio.
 */
class WithholdingXsdMandatoryFieldsTest {

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildValidWithholdingXml() {
        var taxpayer = new WithholdingData.TaxpayerInfo(
                "1", "1",
                "EMPRESA DE PRUEBAS S.A.", "PRUEBAS COMERCIAL",
                "1792146739001",
                "Quito, Av. Amazonas N24-345",
                "Quito, Sucursal Norte", true,
                "12345", "1", "CONTRIBUYENTE RÉGIMEN RIMPE");
        var subject = new WithholdingData.Subject(
                "04", "1790016919001",
                "PROVEEDOR NACIONAL CIA. LTDA.", "01");
        var tax = new WithholdingData.SupportingDocTax(
                "2", "4", new BigDecimal("1000.00"),
                new BigDecimal("15.00"), new BigDecimal("150.00"));
        var retention = new WithholdingData.WithholdingLine(
                "1", "303", new BigDecimal("1000.00"),
                new BigDecimal("10.00"), new BigDecimal("100.00"));
        var payment = new WithholdingData.Payment(
                "20", new BigDecimal("1150.00"));
        var supportDoc = new WithholdingData.SupportingDocument(
                "01", "01", "001001000000234",
                LocalDate.of(2025, 3, 15), null,
                "1503202501179214673900110010010000002340000002341",
                "01", null, null, null, null, null,
                new BigDecimal("1000.00"), new BigDecimal("1150.00"),
                List.of(tax), List.of(retention), List.of(payment));

        return WithholdingXmlBuilder.build(new WithholdingData(
                taxpayer,
                "1504202507179214673900110010010000001230000001231",
                "001", "001", "000000123",
                LocalDate.of(2025, 4, 15),
                subject,
                "03/2025", false,
                List.of(supportDoc),
                Map.of("Email", "proveedor@test.com")));
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
        var xsdUrl = getClass().getResource("/xsd/sri/ComprobanteRetencion_V2.0.0.xsd");
        assertNotNull(xsdUrl, "XSD file must be on classpath");
        var schema = schemaFactory.newSchema(xsdUrl);
        var validator = schema.newValidator();
        validator.validate(new StreamSource(new StringReader(xml)));
    }

    // ── Sanity check ────────────────────────────────────────────────────────

    @Test
    @DisplayName("XML base generado por builder pasa validación XSD")
    void baseXmlIsValid() {
        assertDoesNotThrow(() -> validateAgainstXsd(buildValidWithholdingXml()));
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
            var xml = removeElement(buildValidWithholdingXml(), "infoTributaria", field);
            assertXsdFails(xml);
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
            var xml = removeElement(buildValidWithholdingXml(), "infoCompRetencion", field);
            assertXsdFails(xml);
        }
    }

    // ── docsSustento ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Campos obligatorios faltantes en docsSustento")
    class DocsSustento {

        @Test
        @DisplayName("retención sin nodo <docsSustento> falla XSD")
        void missingDocsSustentoNode() throws Exception {
            var xml = removeElement(buildValidWithholdingXml(), "comprobanteRetencion", "docsSustento");
            assertXsdFails(xml);
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
            var xml = removeElement(buildValidWithholdingXml(), "docSustento", field);
            assertXsdFails(xml);
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
            var xml = removeElement(buildValidWithholdingXml(), "retencion", field);
            assertXsdFails(xml);
        }
    }
}
