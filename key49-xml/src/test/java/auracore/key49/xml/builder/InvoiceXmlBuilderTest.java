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
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import auracore.key49.core.Key49Constants;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para InvoiceXmlBuilder.
 */
class InvoiceXmlBuilderTest {

    private static final DateTimeFormatter SRI_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // ── Helpers ──

    private Document parseXml(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        var builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private String getTextContent(Document doc, String tagName) {
        var nodes = doc.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? nodes.item(0).getTextContent() : null;
    }

    private NodeList getElements(Document doc, String tagName) {
        return doc.getElementsByTagName(tagName);
    }

    private Element getFirstElement(Document doc, String tagName) {
        var nodes = doc.getElementsByTagName(tagName);
        return nodes.getLength() > 0 ? (Element) nodes.item(0) : null;
    }

    // ── Tests de estructura general ──

    @Nested
    @DisplayName("Estructura general del XML")
    class GeneralStructure {

        @Test
        @DisplayName("genera XML con encoding UTF-8 y declaración XML")
        void xmlDeclaration() {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\""));
        }

        @Test
        @DisplayName("elemento raíz es <factura> con atributos id y version")
        void rootElement() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);
            var root = doc.getDocumentElement();

            assertEquals("factura", root.getTagName());
            assertEquals("comprobante", root.getAttribute("id"));
            assertEquals("2.1.0", root.getAttribute("version"));
        }

        @Test
        @DisplayName("contiene los 4 nodos principales: infoTributaria, infoFactura, detalles, infoAdicional")
        void mainNodes() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);
            var root = doc.getDocumentElement();
            var children = root.getChildNodes();

            // Collect element names
            var elementNames = new java.util.ArrayList<String>();
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element e) {
                    elementNames.add(e.getTagName());
                }
            }

            assertEquals(List.of("infoTributaria", "infoFactura", "detalles", "infoAdicional"), elementNames);
        }

        @Test
        @DisplayName("factura sin infoAdicional omite el nodo")
        void noInfoAdicional() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.multiItemInvoice());
            var doc = parseXml(xml);

            assertNull(getFirstElement(doc, "infoAdicional"));
        }

        @Test
        @DisplayName("factura mínima genera XML válido sin nodos opcionales")
        void minimalInvoice() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.minimalInvoice());
            var doc = parseXml(xml);

            // No optional fields
            assertNull(getTextContent(doc, "nombreComercial"));
            assertNull(getTextContent(doc, "dirEstablecimiento"));
            assertNull(getTextContent(doc, "contribuyenteEspecial"));
            assertNull(getTextContent(doc, "agenteRetencion"));
            assertNull(getTextContent(doc, "contribuyenteRimpe"));
            assertNull(getTextContent(doc, "direccionComprador"));
            assertEquals("NO", getTextContent(doc, "obligadoContabilidad"));
        }
    }

    // ── Tests de infoTributaria ──

    @Nested
    @DisplayName("Nodo infoTributaria")
    class InfoTributaria {

        @Test
        @DisplayName("contiene ambiente, tipoEmision, razonSocial, ruc")
        void mandatoryFields() throws Exception {
            var data = InvoiceDataFixtures.simpleInvoice();
            var xml = InvoiceXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals("1", getTextContent(doc, "ambiente"));
            assertEquals("1", getTextContent(doc, "tipoEmision"));
            assertEquals("EMPRESA DEMO S.A.", getTextContent(doc, "razonSocial"));
            assertEquals("1790012345001", getTextContent(doc, "ruc"));
        }

        @Test
        @DisplayName("incluye claveAcceso, codDoc, estab, ptoEmi, secuencial, dirMatriz")
        void documentIdentification() throws Exception {
            var data = InvoiceDataFixtures.simpleInvoice();
            var xml = InvoiceXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals(data.accessKey(), getTextContent(doc, "claveAcceso"));
            assertEquals("01", getTextContent(doc, "codDoc"));
            assertEquals("001", getTextContent(doc, "estab"));
            assertEquals("001", getTextContent(doc, "ptoEmi"));
            assertEquals("000000042", getTextContent(doc, "secuencial"));
            assertEquals("Quito, Av. Principal 123", getTextContent(doc, "dirMatriz"));
        }

        @Test
        @DisplayName("incluye nombreComercial cuando está presente")
        void tradeName() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);

            assertEquals("DEMO", getTextContent(doc, "nombreComercial"));
        }

        @Test
        @DisplayName("incluye contribuyenteRimpe cuando está presente")
        void rimpeContributor() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);

            assertEquals("CONTRIBUYENTE RÉGIMEN RIMPE", getTextContent(doc, "contribuyenteRimpe"));
        }

        @Test
        @DisplayName("omite nombreComercial cuando es null")
        void noTradeName() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.minimalInvoice());
            var doc = parseXml(xml);

            assertNull(getTextContent(doc, "nombreComercial"));
        }
    }

    // ── Tests de infoFactura ──

    @Nested
    @DisplayName("Nodo infoFactura")
    class InfoFactura {

        @Test
        @DisplayName("fechaEmision con formato dd/MM/yyyy")
        void issueDate() throws Exception {
            var data = InvoiceDataFixtures.simpleInvoice();
            var xml = InvoiceXmlBuilder.build(data);
            var doc = parseXml(xml);

            var expected = data.issueDate().format(SRI_DATE_FORMAT);
            assertEquals(expected, getTextContent(doc, "fechaEmision"));
        }

        @Test
        @DisplayName("datos del comprador: tipo, identificación, razón social")
        void recipientData() throws Exception {
            var data = InvoiceDataFixtures.simpleInvoice();
            var xml = InvoiceXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals("04", getTextContent(doc, "tipoIdentificacionComprador"));
            assertEquals("CLIENTE PRUEBA CIA. LTDA.", getTextContent(doc, "razonSocialComprador"));
            assertEquals("1790567890001", getTextContent(doc, "identificacionComprador"));
        }

        @Test
        @DisplayName("totales: totalSinImpuestos, totalDescuento, importeTotal")
        void totals() throws Exception {
            var data = InvoiceDataFixtures.simpleInvoice();
            var xml = InvoiceXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals("50.00", getTextContent(doc, "totalSinImpuestos"));
            assertEquals("0.00", getTextContent(doc, "totalDescuento"));
            assertEquals("57.50", getTextContent(doc, "importeTotal"));
        }

        @Test
        @DisplayName("obligadoContabilidad es SI cuando aplica")
        void requiredAccounting() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);

            assertEquals("SI", getTextContent(doc, "obligadoContabilidad"));
        }

        @Test
        @DisplayName("obligadoContabilidad es NO cuando no aplica")
        void notRequiredAccounting() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.minimalInvoice());
            var doc = parseXml(xml);

            assertEquals("NO", getTextContent(doc, "obligadoContabilidad"));
        }

        @Test
        @DisplayName("dirEstablecimiento incluida cuando existe")
        void establishmentAddress() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);

            assertEquals("Sucursal Norte, Av. 10 de Agosto", getTextContent(doc, "dirEstablecimiento"));
        }

        @Test
        @DisplayName("direccionComprador incluida cuando existe")
        void recipientAddress() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);

            assertEquals("Guayaquil, Av. 9 de Octubre 456", getTextContent(doc, "direccionComprador"));
        }

        @Test
        @DisplayName("moneda incluida cuando existe")
        void currency() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);

            assertEquals("DOLAR", getTextContent(doc, "moneda"));
        }
    }

    // ── Tests de totalConImpuestos ──

    @Nested
    @DisplayName("Nodo totalConImpuestos")
    class TotalConImpuestos {

        @Test
        @DisplayName("un totalImpuesto con IVA 15%")
        void singleTax() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);

            var totalImpuestos = getElements(doc, "totalImpuesto");
            assertEquals(1, totalImpuestos.getLength());

            var ti = (Element) totalImpuestos.item(0);
            assertEquals("2", ti.getElementsByTagName("codigo").item(0).getTextContent());
            assertEquals("4", ti.getElementsByTagName("codigoPorcentaje").item(0).getTextContent());
            assertEquals("50.00", ti.getElementsByTagName("baseImponible").item(0).getTextContent());
            assertEquals("7.50", ti.getElementsByTagName("valor").item(0).getTextContent());
        }

        @Test
        @DisplayName("múltiples totalImpuesto con IVA diferentes tasas")
        void multipleTaxes() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.multiItemInvoice());
            var doc = parseXml(xml);

            var totalImpuestos = getElements(doc, "totalImpuesto");
            assertEquals(2, totalImpuestos.getLength());
        }
    }

    // ── Tests de detalles ──

    @Nested
    @DisplayName("Nodo detalles")
    class Detalles {

        @Test
        @DisplayName("un detalle con todos los campos")
        void singleDetail() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);

            var detalles = getElements(doc, "detalle");
            assertEquals(1, detalles.getLength());

            var det = (Element) detalles.item(0);
            assertEquals("PROD-001", det.getElementsByTagName("codigoPrincipal").item(0).getTextContent());
            assertEquals("7861234567890", det.getElementsByTagName("codigoAuxiliar").item(0).getTextContent());
            assertEquals("Servicio de hosting mensual", det.getElementsByTagName("descripcion").item(0).getTextContent());
            assertEquals("UNIDAD", det.getElementsByTagName("unidadMedida").item(0).getTextContent());
            assertEquals("1.000000", det.getElementsByTagName("cantidad").item(0).getTextContent());
            assertEquals("50.000000", det.getElementsByTagName("precioUnitario").item(0).getTextContent());
            assertEquals("0.00", det.getElementsByTagName("descuento").item(0).getTextContent());
            assertEquals("50.00", det.getElementsByTagName("precioTotalSinImpuesto").item(0).getTextContent());
        }

        @Test
        @DisplayName("múltiples detalles en la factura")
        void multipleDetails() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.multiItemInvoice());
            var doc = parseXml(xml);

            var detalles = getElements(doc, "detalle");
            assertEquals(2, detalles.getLength());
        }

        @Test
        @DisplayName("impuestos del detalle con tarifa, baseImponible, valor")
        void detailTaxes() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);

            var impuestos = getElements(doc, "impuesto");
            assertTrue(impuestos.getLength() >= 1);

            var imp = (Element) impuestos.item(0);
            assertEquals("2", imp.getElementsByTagName("codigo").item(0).getTextContent());
            assertEquals("4", imp.getElementsByTagName("codigoPorcentaje").item(0).getTextContent());
            assertEquals("15.00", imp.getElementsByTagName("tarifa").item(0).getTextContent());
            assertEquals("50.00", imp.getElementsByTagName("baseImponible").item(0).getTextContent());
            assertEquals("7.50", imp.getElementsByTagName("valor").item(0).getTextContent());
        }

        @Test
        @DisplayName("detalle sin códigos opcionales los omite")
        void detailWithoutCodes() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.minimalInvoice());
            var doc = parseXml(xml);

            var detalle = (Element) getElements(doc, "detalle").item(0);
            assertEquals(0, detalle.getElementsByTagName("codigoPrincipal").getLength());
            assertEquals(0, detalle.getElementsByTagName("codigoAuxiliar").getLength());
            assertEquals(0, detalle.getElementsByTagName("unidadMedida").getLength());
        }

        @Test
        @DisplayName("cantidad y precioUnitario con 6 decimales")
        void sixDecimalPlaces() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);

            var cantidad = getTextContent(doc, "cantidad");
            var precioUnitario = getTextContent(doc, "precioUnitario");

            assertTrue(cantidad.contains("."), "cantidad debe tener decimales");
            assertEquals(6, cantidad.split("\\.")[1].length(), "cantidad debe tener 6 decimales");
            assertEquals(6, precioUnitario.split("\\.")[1].length(), "precioUnitario debe tener 6 decimales");
        }
    }

    // ── Tests de pagos ──

    @Nested
    @DisplayName("Nodo pagos")
    class Pagos {

        @Test
        @DisplayName("un pago con formaPago, total, plazo, unidadTiempo")
        void singlePayment() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);

            var pagos = getElements(doc, "pago");
            assertEquals(1, pagos.getLength());

            var pago = (Element) pagos.item(0);
            assertEquals("20", pago.getElementsByTagName("formaPago").item(0).getTextContent());
            assertEquals("57.50", pago.getElementsByTagName("total").item(0).getTextContent());
            assertEquals("0", pago.getElementsByTagName("plazo").item(0).getTextContent());
            assertEquals("dias", pago.getElementsByTagName("unidadTiempo").item(0).getTextContent());
        }

        @Test
        @DisplayName("pago sin plazo ni unidadTiempo los omite")
        void paymentWithoutTerms() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.minimalInvoice());
            var doc = parseXml(xml);

            var pago = (Element) getElements(doc, "pago").item(0);
            assertEquals(0, pago.getElementsByTagName("plazo").getLength());
            assertEquals(0, pago.getElementsByTagName("unidadTiempo").getLength());
        }
    }

    // ── Tests de infoAdicional ──

    @Nested
    @DisplayName("Nodo infoAdicional")
    class InfoAdicional {

        @Test
        @DisplayName("campos adicionales con atributo nombre y texto")
        void additionalFields() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);

            var campos = getElements(doc, "campoAdicional");
            assertEquals(3, campos.getLength());

            var campo1 = (Element) campos.item(0);
            assertEquals("Dirección", campo1.getAttribute("nombre"));
            assertEquals("Guayaquil, Av. 9 de Octubre 456", campo1.getTextContent());
        }

        @Test
        @DisplayName("máximo 15 campos adicionales")
        void maxFifteenFields() throws Exception {
            var today = LocalDate.now(Key49Constants.EC_ZONE);
            var manyFields = new LinkedHashMap<String, String>();
            for (int i = 1; i <= 20; i++) {
                manyFields.put("Campo" + i, "Valor" + i);
            }

            var data = new InvoiceData(
                    InvoiceDataFixtures.defaultTaxpayer(),
                    "0404202601179001234500110010010000000421234567817",
                    "001", "001", "000000042",
                    today,
                    InvoiceDataFixtures.defaultRecipient(),
                    List.of(InvoiceDataFixtures.singleItemWithVat15()),
                    List.of(InvoiceDataFixtures.totalTaxVat15(new BigDecimal("50.00"), new BigDecimal("7.50"))),
                    List.of(InvoiceDataFixtures.defaultPayment(new BigDecimal("57.50"))),
                    new BigDecimal("50.00"),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    new BigDecimal("57.50"),
                    "DOLAR",
                    manyFields
            );

            var xml = InvoiceXmlBuilder.build(data);
            var doc = parseXml(xml);

            var campos = getElements(doc, "campoAdicional");
            assertEquals(15, campos.getLength(), "No debe exceder 15 campos adicionales");
        }
    }

    // ── Tests de validación XSD ──

    @Nested
    @DisplayName("Validación contra XSD factura v2.1.0")
    class XsdValidation {

        @Test
        @DisplayName("factura simple pasa validación XSD")
        void simpleInvoiceXsdValid() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            assertDoesNotThrow(() -> validateAgainstXsd(xml));
        }

        @Test
        @DisplayName("factura multi-ítem pasa validación XSD")
        void multiItemInvoiceXsdValid() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.multiItemInvoice());
            assertDoesNotThrow(() -> validateAgainstXsd(xml));
        }

        @Test
        @DisplayName("factura mínima pasa validación XSD")
        void minimalInvoiceXsdValid() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.minimalInvoice());
            assertDoesNotThrow(() -> validateAgainstXsd(xml));
        }

        private void validateAgainstXsd(String xml) throws SAXException, IOException {
            var schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            var xsdUrl = getClass().getResource("/xsd/sri/factura_V2.1.0.xsd");
            assertNotNull(xsdUrl, "XSD file must be on classpath");
            var schema = schemaFactory.newSchema(xsdUrl);
            var validator = schema.newValidator();
            validator.validate(new StreamSource(new StringReader(xml)));
        }
    }

    // ── Tests de constantes ──

    @Nested
    @DisplayName("Constantes del builder")
    class Constants {

        @Test
        @DisplayName("versión de factura es 2.1.0")
        void version() {
            assertEquals("2.1.0", InvoiceXmlBuilder.FACTURA_VERSION);
        }
    }

    // ── Tests de formato decimal ──

    @Nested
    @DisplayName("Formato de decimales")
    class DecimalFormat {

        @Test
        @DisplayName("montos con 2 decimales, cantidades con 6")
        void decimalPrecision() throws Exception {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            var doc = parseXml(xml);

            // Montos: 2 decimales
            var totalSinImpuestos = getTextContent(doc, "totalSinImpuestos");
            assertEquals("50.00", totalSinImpuestos);

            var importeTotal = getTextContent(doc, "importeTotal");
            assertEquals("57.50", importeTotal);

            // Cantidades: 6 decimales
            var cantidad = getTextContent(doc, "cantidad");
            assertEquals("1.000000", cantidad);

            var precioUnitario = getTextContent(doc, "precioUnitario");
            assertEquals("50.000000", precioUnitario);
        }
    }

    // ── Tests de no-regresión y output XML ──

    @Nested
    @DisplayName("Output completo")
    class FullOutput {

        @Test
        @DisplayName("XML no es null ni vacío")
        void notEmpty() {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            assertNotNull(xml);
            assertFalse(xml.isBlank());
        }

        @Test
        @DisplayName("XML contiene la clave de acceso exacta")
        void containsAccessKey() {
            var data = InvoiceDataFixtures.simpleInvoice();
            var xml = InvoiceXmlBuilder.build(data);
            assertTrue(xml.contains(data.accessKey()));
        }

        @Test
        @DisplayName("XML no contiene propina cuando es cero")
        void noPropina() {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            assertFalse(xml.contains("<propina>"));
        }

        @Test
        @DisplayName("codDoc siempre es 01 para factura")
        void codDoc() {
            var xml = InvoiceXmlBuilder.build(InvoiceDataFixtures.simpleInvoice());
            assertTrue(xml.contains("<codDoc>01</codDoc>"));
        }
    }
}
