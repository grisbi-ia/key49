package auracore.key49.xml.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import auracore.key49.core.Key49Constants;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para CreditNoteXmlBuilder.
 */
class CreditNoteXmlBuilderTest {

    private static final DateTimeFormatter SRI_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ── Helpers ──
    private Document parseXml(String xml) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        var builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private String getTextContent(Document doc, String tagName) {
        var nodes = doc.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
    }

    private Element getFirstElement(Document doc, String tagName) {
        var nodes = doc.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    // ── Tests de estructura general ──
    @Nested
    @DisplayName("Estructura general del XML de nota de crédito")
    class GeneralStructure {

        @Test
        @DisplayName("genera XML con encoding UTF-8")
        void xmlDeclaration() {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\""));
        }

        @Test
        @DisplayName("elemento raíz es <notaCredito> con atributos id y version")
        void rootElement() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            var doc = parseXml(xml);
            var root = doc.getDocumentElement();

            assertEquals("notaCredito", root.getTagName());
            assertEquals("comprobante", root.getAttribute("id"));
            assertEquals("1.1.0", root.getAttribute("version"));
        }

        @Test
        @DisplayName("contiene los nodos principales: infoTributaria, infoNotaCredito, detalles, infoAdicional")
        void mainNodes() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            var doc = parseXml(xml);
            var root = doc.getDocumentElement();
            var children = root.getChildNodes();

            var elementNames = new java.util.ArrayList<String>();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element e) {
                    elementNames.add(e.getTagName());
                }
            }

            assertEquals(List.of("infoTributaria", "infoNotaCredito", "detalles", "infoAdicional"), elementNames);
        }

        @Test
        @DisplayName("nota de crédito sin infoAdicional omite el nodo")
        void noInfoAdicional() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.multiItemCreditNote());
            var doc = parseXml(xml);

            assertNull(getFirstElement(doc, "infoAdicional"));
        }

        @Test
        @DisplayName("nota de crédito mínima genera XML válido sin nodos opcionales")
        void minimalCreditNote() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.minimalCreditNote());
            var doc = parseXml(xml);

            assertNull(getTextContent(doc, "nombreComercial"));
            assertNull(getTextContent(doc, "dirEstablecimiento"));
            assertNull(getTextContent(doc, "contribuyenteEspecial"));
            assertNull(getTextContent(doc, "agenteRetencion"));
            assertNull(getTextContent(doc, "contribuyenteRimpe"));
            assertEquals("NO", getTextContent(doc, "obligadoContabilidad"));
        }
    }

    // ── Tests de infoTributaria ──
    @Nested
    @DisplayName("infoTributaria")
    class InfoTributaria {

        @Test
        @DisplayName("codDoc es 04 para nota de crédito")
        void codDoc() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            var doc = parseXml(xml);

            assertEquals("04", getTextContent(doc, "codDoc"));
        }

        @Test
        @DisplayName("clave de acceso se incluye correctamente")
        void claveAcceso() throws Exception {
            var data = CreditNoteDataFixtures.simpleCreditNote();
            var xml = CreditNoteXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals(data.accessKey(), getTextContent(doc, "claveAcceso"));
        }

        @Test
        @DisplayName("datos del emisor se incluyen correctamente")
        void issuerData() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            var doc = parseXml(xml);

            assertEquals("1790012345001", getTextContent(doc, "ruc"));
            assertEquals("EMPRESA DEMO S.A.", getTextContent(doc, "razonSocial"));
            assertEquals("DEMO", getTextContent(doc, "nombreComercial"));
            assertEquals("001", getTextContent(doc, "estab"));
            assertEquals("001", getTextContent(doc, "ptoEmi"));
            assertEquals("000000042", getTextContent(doc, "secuencial"));
        }
    }

    // ── Tests de infoNotaCredito ──
    @Nested
    @DisplayName("infoNotaCredito")
    class InfoNotaCredito {

        @Test
        @DisplayName("fecha de emisión en formato dd/MM/yyyy")
        void fechaEmision() throws Exception {
            var data = CreditNoteDataFixtures.simpleCreditNote();
            var xml = CreditNoteXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals(data.issueDate().format(SRI_DATE_FORMAT), getTextContent(doc, "fechaEmision"));
        }

        @Test
        @DisplayName("datos del comprador se incluyen correctamente")
        void recipientData() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            var doc = parseXml(xml);

            assertEquals("04", getTextContent(doc, "tipoIdentificacionComprador"));
            assertEquals("1790567890001", getTextContent(doc, "identificacionComprador"));
            assertEquals("CLIENTE PRUEBA CIA. LTDA.", getTextContent(doc, "razonSocialComprador"));
        }

        @Test
        @DisplayName("datos del documento modificado se incluyen correctamente")
        void modifiedDocumentData() throws Exception {
            var data = CreditNoteDataFixtures.simpleCreditNote();
            var xml = CreditNoteXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals("01", getTextContent(doc, "codDocModificado"));
            assertEquals("001-001-000000001", getTextContent(doc, "numDocModificado"));
            assertEquals(data.modifiedDocumentDate().format(SRI_DATE_FORMAT),
                    getTextContent(doc, "fechaEmisionDocSustento"));
        }

        @Test
        @DisplayName("motivo se incluye correctamente")
        void reason() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            var doc = parseXml(xml);

            assertEquals("Devolución de producto", getTextContent(doc, "motivo"));
        }

        @Test
        @DisplayName("totalSinImpuestos y valorModificacion se incluyen correctamente")
        void totals() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            var doc = parseXml(xml);

            assertEquals("50.00", getTextContent(doc, "totalSinImpuestos"));
            assertEquals("57.50", getTextContent(doc, "valorModificacion"));
        }

        @Test
        @DisplayName("moneda se incluye correctamente")
        void currency() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            var doc = parseXml(xml);

            assertEquals("DOLAR", getTextContent(doc, "moneda"));
        }

        @Test
        @DisplayName("totalConImpuestos contiene los impuestos totalizados")
        void totalConImpuestos() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            var doc = parseXml(xml);

            var totalConImpuestos = getFirstElement(doc, "totalConImpuestos");
            assertNotNull(totalConImpuestos);

            var totalImpuestos = totalConImpuestos.getElementsByTagName("totalImpuesto");
            assertEquals(1, totalImpuestos.getLength());

            var impuesto = (Element) totalImpuestos.item(0);
            assertEquals("2", impuesto.getElementsByTagName("codigo").item(0).getTextContent());
            assertEquals("4", impuesto.getElementsByTagName("codigoPorcentaje").item(0).getTextContent());
        }

        @Test
        @DisplayName("múltiples impuestos totalizados se generan correctamente")
        void multipleTotalTaxes() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.multiItemCreditNote());
            var doc = parseXml(xml);

            var totalConImpuestos = getFirstElement(doc, "totalConImpuestos");
            assertNotNull(totalConImpuestos);

            var totalImpuestos = totalConImpuestos.getElementsByTagName("totalImpuesto");
            assertEquals(2, totalImpuestos.getLength());
        }
    }

    // ── Tests de detalles ──
    @Nested
    @DisplayName("Detalles de ítems")
    class Detalles {

        @Test
        @DisplayName("ítems se generan con codigoInterno en lugar de codigoPrincipal")
        void itemWithInternalCode() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            var doc = parseXml(xml);

            assertEquals("PROD-001", getTextContent(doc, "codigoInterno"));
            assertNull(getFirstElement(doc, "codigoPrincipal"));
        }

        @Test
        @DisplayName("codigoAdicional se incluye cuando está presente")
        void additionalCode() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            var doc = parseXml(xml);

            assertEquals("7861234567890", getTextContent(doc, "codigoAdicional"));
        }

        @Test
        @DisplayName("múltiples ítems se generan correctamente")
        void multipleItems() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.multiItemCreditNote());
            var doc = parseXml(xml);

            var detalles = getFirstElement(doc, "detalles");
            assertNotNull(detalles);

            var items = detalles.getElementsByTagName("detalle");
            assertEquals(2, items.getLength());
        }

        @Test
        @DisplayName("impuestos del ítem se incluyen correctamente")
        void itemTaxes() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            var doc = parseXml(xml);

            var detalle = getFirstElement(doc, "detalle");
            assertNotNull(detalle);

            var impuestos = detalle.getElementsByTagName("impuesto");
            assertEquals(1, impuestos.getLength());
        }
    }

    // ── Tests de infoAdicional ──
    @Nested
    @DisplayName("Información adicional")
    class InfoAdicional {

        @Test
        @DisplayName("campos adicionales se generan correctamente")
        void additionalFields() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            var doc = parseXml(xml);

            var infoAdicional = getFirstElement(doc, "infoAdicional");
            assertNotNull(infoAdicional);

            var campos = infoAdicional.getElementsByTagName("campoAdicional");
            assertEquals(2, campos.getLength());
        }
    }

    // ── Tests de validación XSD ──
    @Nested
    @DisplayName("Validación contra XSD nota de crédito v1.1.0")
    class XsdValidation {

        @Test
        @DisplayName("nota de crédito simple pasa validación XSD")
        void simpleCreditNoteXsdValid() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.simpleCreditNote());
            assertDoesNotThrow(() -> validateAgainstXsd(xml));
        }

        @Test
        @DisplayName("nota de crédito multi-ítem pasa validación XSD")
        void multiItemCreditNoteXsdValid() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.multiItemCreditNote());
            assertDoesNotThrow(() -> validateAgainstXsd(xml));
        }

        @Test
        @DisplayName("nota de crédito mínima pasa validación XSD")
        void minimalCreditNoteXsdValid() throws Exception {
            var xml = CreditNoteXmlBuilder.build(CreditNoteDataFixtures.minimalCreditNote());
            assertDoesNotThrow(() -> validateAgainstXsd(xml));
        }

        private void validateAgainstXsd(String xml) throws SAXException, IOException {
            var schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            var xsdUrl = getClass().getResource("/xsd/sri/NotaCredito_V1.1.0.xsd");
            assertNotNull(xsdUrl, "XSD file must be on classpath");
            var schema = schemaFactory.newSchema(xsdUrl);
            var validator = schema.newValidator();
            validator.validate(new StreamSource(new StringReader(xml)));
        }
    }
}
