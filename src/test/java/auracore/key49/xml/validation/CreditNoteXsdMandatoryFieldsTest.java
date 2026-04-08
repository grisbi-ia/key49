package auracore.key49.xml.validation;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;

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

import auracore.key49.core.Key49Constants;
import auracore.key49.xml.builder.CreditNoteData;
import auracore.key49.xml.builder.CreditNoteXmlBuilder;

/**
 * Tests negativos que verifican que el XSD nota de crédito v1.1.0 del SRI
 * rechaza el XML cuando falta un campo obligatorio.
 */
class CreditNoteXsdMandatoryFieldsTest {

    // ── Helpers ──

    private String buildValidCreditNoteXml() {
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        var taxpayer = new CreditNoteData.TaxpayerInfo(
                "1", "1",
                "EMPRESA DEMO S.A.", "DEMO",
                "1790012345001",
                "Quito, Av. Principal 123",
                "Sucursal Norte", true, null, null,
                "CONTRIBUYENTE RÉGIMEN RIMPE"
        );
        var recipient = new CreditNoteData.Recipient(
                "04", "1790567890001",
                "CLIENTE PRUEBA CIA. LTDA."
        );
        var tax = new CreditNoteData.Tax("2", "4",
                new BigDecimal("15.00"),
                new BigDecimal("50.00"),
                new BigDecimal("7.50"));
        var item = new CreditNoteData.Item(
                "PROD-001", null,
                "Servicio de hosting mensual",
                BigDecimal.ONE,
                new BigDecimal("50.00"),
                BigDecimal.ZERO,
                new BigDecimal("50.00"),
                List.of(tax)
        );
        var totalTax = new CreditNoteData.TotalTax("2", "4",
                new BigDecimal("50.00"),
                new BigDecimal("7.50"));

        return CreditNoteXmlBuilder.build(new CreditNoteData(
                taxpayer,
                "0404202601179001234500110010010000000421234567817",
                "001", "001", "000000042",
                today,
                recipient,
                "01",
                "001-001-000000001",
                today.minusDays(5),
                "Devolución de producto",
                List.of(item),
                List.of(totalTax),
                new BigDecimal("50.00"),
                new BigDecimal("57.50"),
                "DOLAR",
                new LinkedHashMap<>() {{
                    put("Dirección", "Guayaquil");
                    put("Email", "test@example.com");
                }}
        ));
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
        var xsdUrl = getClass().getResource("/xsd/sri/NotaCredito_V1.1.0.xsd");
        assertNotNull(xsdUrl, "XSD file must be on classpath");
        var schema = schemaFactory.newSchema(xsdUrl);
        var validator = schema.newValidator();
        validator.validate(new StreamSource(new StringReader(xml)));
    }

    // ── Sanity check ──

    @Test
    @DisplayName("XML base generado por builder pasa validación XSD")
    void baseXmlIsValid() {
        assertDoesNotThrow(() -> validateAgainstXsd(buildValidCreditNoteXml()));
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
            var xml = removeElement(buildValidCreditNoteXml(), "infoTributaria", field);
            assertXsdFails(xml);
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
            var xml = removeElement(buildValidCreditNoteXml(), "infoNotaCredito", field);
            assertXsdFails(xml);
        }
    }

    // ── detalles ──

    @Nested
    @DisplayName("Campos obligatorios faltantes en detalles")
    class Detalles {

        @Test
        @DisplayName("nota de crédito sin nodo <detalles> falla XSD")
        void missingDetallesNode() throws Exception {
            var xml = removeElement(buildValidCreditNoteXml(), "notaCredito", "detalles");
            assertXsdFails(xml);
        }

        @ParameterizedTest(name = "sin <{0}> en detalle falla XSD")
        @ValueSource(strings = {
                "descripcion", "cantidad", "precioUnitario",
                "precioTotalSinImpuesto", "impuestos"
        })
        @DisplayName("remover campo obligatorio de detalle causa fallo XSD")
        void missingMandatoryFieldInDetail(String field) throws Exception {
            var xml = removeElement(buildValidCreditNoteXml(), "detalle", field);
            assertXsdFails(xml);
        }
    }
}
