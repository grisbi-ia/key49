package auracore.key49.xml.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.StringReader;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para DebitNoteXmlBuilder.
 */

class DebitNoteXmlBuilderTest {

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
    @DisplayName("Estructura general del XML de nota de débito")
    class GeneralStructure {

        @Test
        @DisplayName("genera XML con encoding UTF-8")
        void xmlDeclaration() {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.simpleDebitNote());
            assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\""));
        }

        @Test
        @DisplayName("elemento raíz es <notaDebito> con atributos id y version")
        void rootElement() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.simpleDebitNote());
            var doc = parseXml(xml);
            var root = doc.getDocumentElement();

            assertEquals("notaDebito", root.getTagName());
            assertEquals("comprobante", root.getAttribute("id"));
            assertEquals("1.0.0", root.getAttribute("version"));
        }

        @Test
        @DisplayName("contiene los nodos principales: infoTributaria, infoNotaDebito, motivos, infoAdicional")
        void mainNodes() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.simpleDebitNote());
            var doc = parseXml(xml);
            var root = doc.getDocumentElement();
            var children = root.getChildNodes();

            var elementNames = new java.util.ArrayList<String>();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element e) {
                    elementNames.add(e.getTagName());
                }
            }

            assertEquals(List.of("infoTributaria", "infoNotaDebito", "motivos", "infoAdicional"), elementNames);
        }

        @Test
        @DisplayName("nota de débito sin infoAdicional omite el nodo")
        void noInfoAdicional() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.multiReasonDebitNote());
            var doc = parseXml(xml);

            assertNull(getFirstElement(doc, "infoAdicional"));
        }

        @Test
        @DisplayName("nota de débito mínima genera XML válido sin nodos opcionales")
        void minimalDebitNote() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.minimalDebitNote());
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
        @DisplayName("codDoc es 05 para nota de débito")
        void codDoc() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.simpleDebitNote());
            var doc = parseXml(xml);

            assertEquals("05", getTextContent(doc, "codDoc"));
        }

        @Test
        @DisplayName("clave de acceso se incluye correctamente")
        void claveAcceso() throws Exception {
            var data = DebitNoteDataFixtures.simpleDebitNote();
            var xml = DebitNoteXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals(data.accessKey(), getTextContent(doc, "claveAcceso"));
        }

        @Test
        @DisplayName("datos del emisor se incluyen correctamente")
        void issuerData() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.simpleDebitNote());
            var doc = parseXml(xml);

            assertEquals("1790012345001", getTextContent(doc, "ruc"));
            assertEquals("EMPRESA DEMO S.A.", getTextContent(doc, "razonSocial"));
            assertEquals("DEMO", getTextContent(doc, "nombreComercial"));
            assertEquals("001", getTextContent(doc, "estab"));
            assertEquals("001", getTextContent(doc, "ptoEmi"));
            assertEquals("000000042", getTextContent(doc, "secuencial"));
        }
    }

    // ── Tests de infoNotaDebito ──

    @Nested
    @DisplayName("infoNotaDebito")
    class InfoNotaDebito {

        @Test
        @DisplayName("fecha de emisión en formato dd/MM/yyyy")
        void fechaEmision() throws Exception {
            var data = DebitNoteDataFixtures.simpleDebitNote();
            var xml = DebitNoteXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals(data.issueDate().format(SRI_DATE_FORMAT), getTextContent(doc, "fechaEmision"));
        }

        @Test
        @DisplayName("datos del comprador se incluyen correctamente")
        void recipientData() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.simpleDebitNote());
            var doc = parseXml(xml);

            assertEquals("04", getTextContent(doc, "tipoIdentificacionComprador"));
            assertEquals("1790567890001", getTextContent(doc, "identificacionComprador"));
            assertEquals("CLIENTE PRUEBA CIA. LTDA.", getTextContent(doc, "razonSocialComprador"));
        }

        @Test
        @DisplayName("datos del documento modificado se incluyen correctamente")
        void modifiedDocumentData() throws Exception {
            var data = DebitNoteDataFixtures.simpleDebitNote();
            var xml = DebitNoteXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals("01", getTextContent(doc, "codDocModificado"));
            assertEquals("001-001-000000001", getTextContent(doc, "numDocModificado"));
            assertEquals(data.modifiedDocumentDate().format(SRI_DATE_FORMAT),
                    getTextContent(doc, "fechaEmisionDocSustento"));
        }

        @Test
        @DisplayName("totalSinImpuestos y valorTotal se incluyen correctamente")
        void totals() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.simpleDebitNote());
            var doc = parseXml(xml);

            assertEquals("50.00", getTextContent(doc, "totalSinImpuestos"));
            assertEquals("57.50", getTextContent(doc, "valorTotal"));
        }

        @Test
        @DisplayName("impuestos contienen tarifa obligatoria")
        void taxesWithRate() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.simpleDebitNote());
            var doc = parseXml(xml);

            var impuestos = getFirstElement(doc, "impuestos");
            assertNotNull(impuestos);

            var impuestoList = impuestos.getElementsByTagName("impuesto");
            assertEquals(1, impuestoList.getLength());

            var impuesto = (Element) impuestoList.item(0);
            assertEquals("2", impuesto.getElementsByTagName("codigo").item(0).getTextContent());
            assertEquals("4", impuesto.getElementsByTagName("codigoPorcentaje").item(0).getTextContent());
            assertEquals("15.00", impuesto.getElementsByTagName("tarifa").item(0).getTextContent());
            assertEquals("50.00", impuesto.getElementsByTagName("baseImponible").item(0).getTextContent());
            assertEquals("7.50", impuesto.getElementsByTagName("valor").item(0).getTextContent());
        }

        @Test
        @DisplayName("múltiples impuestos se generan correctamente")
        void multipleTaxes() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.multiReasonDebitNote());
            var doc = parseXml(xml);

            var impuestos = getFirstElement(doc, "impuestos");
            assertNotNull(impuestos);

            var impuestoList = impuestos.getElementsByTagName("impuesto");
            assertEquals(2, impuestoList.getLength());
        }

        @Test
        @DisplayName("pagos se incluyen cuando están presentes")
        void paymentsIncluded() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.simpleDebitNote());
            var doc = parseXml(xml);

            var pagos = getFirstElement(doc, "pagos");
            assertNotNull(pagos);

            var pagoList = pagos.getElementsByTagName("pago");
            assertEquals(1, pagoList.getLength());

            var pago = (Element) pagoList.item(0);
            assertEquals("01", pago.getElementsByTagName("formaPago").item(0).getTextContent());
            assertEquals("57.50", pago.getElementsByTagName("total").item(0).getTextContent());
            assertEquals("30", pago.getElementsByTagName("plazo").item(0).getTextContent());
            assertEquals("dias", pago.getElementsByTagName("unidadTiempo").item(0).getTextContent());
        }

        @Test
        @DisplayName("pagos se omiten cuando no están presentes")
        void paymentsOmitted() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.multiReasonDebitNote());
            var doc = parseXml(xml);

            assertNull(getFirstElement(doc, "pagos"));
        }
    }

    // ── Tests de motivos ──

    @Nested
    @DisplayName("Motivos")
    class Motivos {

        @Test
        @DisplayName("motivo contiene razón y valor")
        void singleReason() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.simpleDebitNote());
            var doc = parseXml(xml);

            var motivos = getFirstElement(doc, "motivos");
            assertNotNull(motivos);

            var motivoList = motivos.getElementsByTagName("motivo");
            assertEquals(1, motivoList.getLength());

            var motivo = (Element) motivoList.item(0);
            assertEquals("Intereses por mora en pago",
                    motivo.getElementsByTagName("razon").item(0).getTextContent());
            assertEquals("50.00",
                    motivo.getElementsByTagName("valor").item(0).getTextContent());
        }

        @Test
        @DisplayName("múltiples motivos se generan correctamente")
        void multipleReasons() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.multiReasonDebitNote());
            var doc = parseXml(xml);

            var motivos = getFirstElement(doc, "motivos");
            assertNotNull(motivos);

            var motivoList = motivos.getElementsByTagName("motivo");
            assertEquals(2, motivoList.getLength());

            var motivo2 = (Element) motivoList.item(1);
            assertEquals("Gastos administrativos de cobranza",
                    motivo2.getElementsByTagName("razon").item(0).getTextContent());
            assertEquals("25.00",
                    motivo2.getElementsByTagName("valor").item(0).getTextContent());
        }
    }

    // ── Tests de infoAdicional ──

    @Nested
    @DisplayName("Información adicional")
    class InfoAdicional {

        @Test
        @DisplayName("campos adicionales se generan correctamente")
        void additionalFields() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.simpleDebitNote());
            var doc = parseXml(xml);

            var infoAdicional = getFirstElement(doc, "infoAdicional");
            assertNotNull(infoAdicional);

            var campos = infoAdicional.getElementsByTagName("campoAdicional");
            assertEquals(2, campos.getLength());
        }
    }

    // ── Tests de validación XSD ──

    @Nested
    @DisplayName("Validación contra XSD nota de débito v1.0.0")
    class XsdValidation {

        @Test
        @DisplayName("nota de débito simple pasa validación XSD")
        void simpleDebitNoteXsdValid() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.simpleDebitNote());
            assertDoesNotThrow(() -> validateAgainstXsd(xml));
        }

        @Test
        @DisplayName("nota de débito con múltiples motivos pasa validación XSD")
        void multiReasonDebitNoteXsdValid() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.multiReasonDebitNote());
            assertDoesNotThrow(() -> validateAgainstXsd(xml));
        }

        @Test
        @DisplayName("nota de débito mínima pasa validación XSD")
        void minimalDebitNoteXsdValid() throws Exception {
            var xml = DebitNoteXmlBuilder.build(DebitNoteDataFixtures.minimalDebitNote());
            assertDoesNotThrow(() -> validateAgainstXsd(xml));
        }

        private void validateAgainstXsd(String xml) throws SAXException, IOException {
            var schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            var xsdUrl = getClass().getResource("/xsd/sri/NotaDebito_V1.0.0.xsd");
            assertNotNull(xsdUrl, "XSD file must be on classpath");
            var schema = schemaFactory.newSchema(xsdUrl);
            var validator = schema.newValidator();
            validator.validate(new StreamSource(new StringReader(xml)));
        }
    }
}
