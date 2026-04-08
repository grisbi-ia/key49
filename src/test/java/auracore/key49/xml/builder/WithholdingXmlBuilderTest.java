package auracore.key49.xml.builder;

import java.io.IOException;
import java.io.StringReader;
import java.time.format.DateTimeFormatter;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

class WithholdingXmlBuilderTest {

    private static final DateTimeFormatter SRI_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

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

    private NodeList getElements(Document doc, String tagName) {
        return doc.getElementsByTagName(tagName);
    }

    @Nested
    @DisplayName("Estructura general del XML de comprobante de retención")
    class GeneralStructure {

        @Test
        @DisplayName("genera XML con encoding UTF-8")
        void xmlDeclaration() {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            assertTrue(xml.contains("<?xml"));
            assertTrue(xml.contains("UTF-8"));
        }

        @Test
        @DisplayName("elemento raíz es <comprobanteRetencion> con atributos id y version")
        void rootElement() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);

            var root = doc.getDocumentElement();
            assertEquals("comprobanteRetencion", root.getTagName());
            assertEquals("comprobante", root.getAttribute("id"));
            assertEquals("2.0.0", root.getAttribute("version"));
        }

        @Test
        @DisplayName("contiene los nodos principales: infoTributaria, infoCompRetencion, docsSustento, infoAdicional")
        void mainNodes() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);

            assertNotNull(getFirstElement(doc, "infoTributaria"));
            assertNotNull(getFirstElement(doc, "infoCompRetencion"));
            assertNotNull(getFirstElement(doc, "docsSustento"));
            assertNotNull(getFirstElement(doc, "infoAdicional"));
        }

        @Test
        @DisplayName("comprobante sin infoAdicional omite el nodo")
        void noInfoAdicional() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.minimalWithholding());
            var doc = parseXml(xml);

            assertNull(getFirstElement(doc, "infoAdicional"));
        }

        @Test
        @DisplayName("comprobante mínimo genera XML válido sin nodos opcionales")
        void minimalWithholding() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.minimalWithholding());
            var doc = parseXml(xml);

            assertNotNull(doc.getDocumentElement());
            assertEquals("comprobanteRetencion", doc.getDocumentElement().getTagName());
        }
    }

    @Nested
    @DisplayName("infoTributaria")
    class InfoTributaria {

        @Test
        @DisplayName("codDoc es 07 para retención")
        void codDoc() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);

            assertEquals("07", getTextContent(doc, "codDoc"));
        }

        @Test
        @DisplayName("claveAcceso se incluye correctamente")
        void claveAcceso() throws Exception {
            var data = WithholdingDataFixtures.simpleWithholding();
            var xml = WithholdingXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals(data.accessKey(), getTextContent(doc, "claveAcceso"));
        }

        @Test
        @DisplayName("datos del emisor se incluyen correctamente")
        void issuerData() throws Exception {
            var data = WithholdingDataFixtures.simpleWithholding();
            var xml = WithholdingXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals("1", getTextContent(doc, "ambiente"));
            assertEquals("1", getTextContent(doc, "tipoEmision"));
            assertEquals(data.taxpayer().legalName(), getTextContent(doc, "razonSocial"));
            assertEquals(data.taxpayer().ruc(), getTextContent(doc, "ruc"));
            assertEquals("001", getTextContent(doc, "estab"));
            assertEquals("001", getTextContent(doc, "ptoEmi"));
        }
    }

    @Nested
    @DisplayName("infoCompRetencion")
    class InfoCompRetencion {

        @Test
        @DisplayName("fechaEmision en formato dd/MM/yyyy")
        void fechaEmision() throws Exception {
            var data = WithholdingDataFixtures.simpleWithholding();
            var xml = WithholdingXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals(data.issueDate().format(SRI_DATE_FORMAT),
                    getTextContent(doc, "fechaEmision"));
        }

        @Test
        @DisplayName("datos del sujeto retenido se incluyen")
        void subjectData() throws Exception {
            var data = WithholdingDataFixtures.simpleWithholding();
            var xml = WithholdingXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals("04", getTextContent(doc, "tipoIdentificacionSujetoRetenido"));
            assertEquals("PROVEEDOR NACIONAL CIA. LTDA.",
                    getTextContent(doc, "razonSocialSujetoRetenido"));
            assertEquals("1790016919001",
                    getTextContent(doc, "identificacionSujetoRetenido"));
        }

        @Test
        @DisplayName("tipoSujetoRetenido incluido cuando tiene valor")
        void tipoSujetoRetenido() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);

            assertEquals("01", getTextContent(doc, "tipoSujetoRetenido"));
        }

        @Test
        @DisplayName("tipoSujetoRetenido omitido cuando es null")
        void tipoSujetoRetenidoOmitted() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.minimalWithholding());
            var doc = parseXml(xml);

            assertNull(getFirstElement(doc, "tipoSujetoRetenido"));
        }

        @Test
        @DisplayName("parteRel se incluye como SI o NO")
        void parteRel() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);
            assertEquals("NO", getTextContent(doc, "parteRel"));

            var xmlRelated = WithholdingXmlBuilder.build(
                    WithholdingDataFixtures.multiDocWithholding());
            var docRelated = parseXml(xmlRelated);
            assertEquals("SI", getTextContent(docRelated, "parteRel"));
        }

        @Test
        @DisplayName("periodoFiscal en formato MM/yyyy")
        void periodoFiscal() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);

            assertEquals("03/2025", getTextContent(doc, "periodoFiscal"));
        }

        @Test
        @DisplayName("obligadoContabilidad incluido cuando requiredAccounting=true")
        void obligadoContabilidad() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);
            assertEquals("SI", getTextContent(doc, "obligadoContabilidad"));

            var xmlMinimal = WithholdingXmlBuilder.build(
                    WithholdingDataFixtures.minimalWithholding());
            var docMinimal = parseXml(xmlMinimal);
            assertNull(getFirstElement(docMinimal, "obligadoContabilidad"));
        }
    }

    @Nested
    @DisplayName("docsSustento")
    class DocsSustento {

        @Test
        @DisplayName("documento de sustento con datos básicos")
        void basicSupportingDoc() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);

            assertEquals("01", getTextContent(doc, "codSustento"));
            assertEquals("01", getTextContent(doc, "codDocSustento"));
            assertEquals("001001000000234", getTextContent(doc, "numDocSustento"));
            assertEquals("15/03/2025", getTextContent(doc, "fechaEmisionDocSustento"));
            assertEquals("01", getTextContent(doc, "pagoLocExt"));
        }

        @Test
        @DisplayName("múltiples documentos de sustento")
        void multipleSupportingDocs() throws Exception {
            var xml = WithholdingXmlBuilder.build(
                    WithholdingDataFixtures.multiDocWithholding());
            var doc = parseXml(xml);

            var docsSustento = getElements(doc, "docSustento");
            assertEquals(2, docsSustento.getLength());
        }

        @Test
        @DisplayName("totalSinImpuestos e importeTotal presentes")
        void totals() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);

            assertEquals("1000.00", getTextContent(doc, "totalSinImpuestos"));
            assertEquals("1150.00", getTextContent(doc, "importeTotal"));
        }

        @Test
        @DisplayName("numAutDocSustento incluido cuando tiene valor")
        void authorizationNumber() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            assertTrue(xml.contains("numAutDocSustento"));
        }
    }

    @Nested
    @DisplayName("Impuestos del documento de sustento")
    class ImpuestosDocSustento {

        @Test
        @DisplayName("impuesto con código, porcentaje, base y valor")
        void singleTax() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);

            assertEquals("2", getTextContent(doc, "codImpuestoDocSustento"));
            assertNotNull(getTextContent(doc, "codigoPorcentaje"));
            assertNotNull(getTextContent(doc, "tarifa"));
            assertNotNull(getTextContent(doc, "valorImpuesto"));
        }
    }

    @Nested
    @DisplayName("Retenciones")
    class Retenciones {

        @Test
        @DisplayName("retención con código, codigoRetencion, base, porcentaje y valor")
        void singleRetention() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);

            var retenciones = getElements(doc, "retencion");
            assertTrue(retenciones.getLength() >= 1);

            var first = (Element) retenciones.item(0);
            assertNotNull(first.getElementsByTagName("codigo").item(0));
            assertNotNull(first.getElementsByTagName("codigoRetencion").item(0));
            assertNotNull(first.getElementsByTagName("baseImponible").item(0));
            assertNotNull(first.getElementsByTagName("porcentajeRetener").item(0));
            assertNotNull(first.getElementsByTagName("valorRetenido").item(0));
        }

        @Test
        @DisplayName("múltiples retenciones en un documento de sustento")
        void multipleRetentions() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);

            var retenciones = getElements(doc, "retencion");
            assertEquals(2, retenciones.getLength());
        }

        @Test
        @DisplayName("retención de renta con código 1")
        void rentaRetention() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);

            var retenciones = getElements(doc, "retencion");
            var first = (Element) retenciones.item(0);
            assertEquals("1", first.getElementsByTagName("codigo")
                    .item(0).getTextContent());
            assertEquals("303", first.getElementsByTagName("codigoRetencion")
                    .item(0).getTextContent());
            assertEquals("1000.00", first.getElementsByTagName("baseImponible")
                    .item(0).getTextContent());
            assertEquals("10.00", first.getElementsByTagName("porcentajeRetener")
                    .item(0).getTextContent());
            assertEquals("100.00", first.getElementsByTagName("valorRetenido")
                    .item(0).getTextContent());
        }

        @Test
        @DisplayName("retención de IVA con código 2")
        void ivaRetention() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);

            var retenciones = getElements(doc, "retencion");
            var second = (Element) retenciones.item(1);
            assertEquals("2", second.getElementsByTagName("codigo")
                    .item(0).getTextContent());
            assertEquals("725", second.getElementsByTagName("codigoRetencion")
                    .item(0).getTextContent());
        }
    }

    @Nested
    @DisplayName("Pagos del documento de sustento")
    class Pagos {

        @Test
        @DisplayName("pagos incluidos con formaPago y total")
        void paymentsIncluded() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);

            assertNotNull(getFirstElement(doc, "pagos"));
            assertEquals("20", getTextContent(doc, "formaPago"));
        }

        @Test
        @DisplayName("pagos incluidos en docSustento mínimo")
        void paymentsIncludedInMinimal() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.minimalWithholding());
            var doc = parseXml(xml);

            var pagosNodes = doc.getElementsByTagName("pagos");
            assertTrue(pagosNodes.getLength() > 0, "pagos element must be present per XSD");
        }
    }

    @Nested
    @DisplayName("Información adicional")
    class InfoAdicional {

        @Test
        @DisplayName("campos adicionales con nombre y valor")
        void additionalFields() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            var doc = parseXml(xml);

            var campos = getElements(doc, "campoAdicional");
            assertTrue(campos.getLength() > 0);

            var first = (Element) campos.item(0);
            assertNotNull(first.getAttribute("nombre"));
            assertNotNull(first.getTextContent());
        }
    }

    // ── Tests de validación XSD ──
    @Nested
    @DisplayName("Validación contra XSD retención v2.0.0")
    class XsdValidation {

        @Test
        @DisplayName("retención simple pasa validación XSD")
        void simpleWithholdingXsdValid() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.simpleWithholding());
            assertDoesNotThrow(() -> validateAgainstXsd(xml));
        }

        @Test
        @DisplayName("retención con múltiples docs sustento pasa validación XSD")
        void multiDocWithholdingXsdValid() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.multiDocWithholding());
            assertDoesNotThrow(() -> validateAgainstXsd(xml));
        }

        @Test
        @DisplayName("retención mínima pasa validación XSD")
        void minimalWithholdingXsdValid() throws Exception {
            var xml = WithholdingXmlBuilder.build(WithholdingDataFixtures.minimalWithholding());
            assertDoesNotThrow(() -> validateAgainstXsd(xml));
        }

        private void validateAgainstXsd(String xml) throws SAXException, IOException {
            var schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            var xsdUrl = getClass().getResource("/xsd/sri/ComprobanteRetencion_V2.0.0.xsd");
            assertNotNull(xsdUrl, "XSD file must be on classpath");
            var schema = schemaFactory.newSchema(xsdUrl);
            var validator = schema.newValidator();
            validator.validate(new StreamSource(new StringReader(xml)));
        }
    }
}
