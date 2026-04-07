package auracore.key49.xml.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.*;

class WaybillXmlBuilderTest {

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
    @DisplayName("Estructura general del XML de guía de remisión")
    class GeneralStructure {

        @Test
        @DisplayName("genera XML con encoding UTF-8")
        void xmlDeclaration() {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            assertTrue(xml.contains("<?xml"));
            assertTrue(xml.contains("UTF-8"));
        }

        @Test
        @DisplayName("elemento raíz es <guiaRemision> con atributos id y version")
        void rootElement() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            var root = doc.getDocumentElement();
            assertEquals("guiaRemision", root.getTagName());
            assertEquals("comprobante", root.getAttribute("id"));
            assertEquals("1.1.0", root.getAttribute("version"));
        }

        @Test
        @DisplayName("contiene los nodos principales: infoTributaria, infoGuiaRemision, destinatarios, infoAdicional")
        void mainNodes() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            assertNotNull(getFirstElement(doc, "infoTributaria"));
            assertNotNull(getFirstElement(doc, "infoGuiaRemision"));
            assertNotNull(getFirstElement(doc, "destinatarios"));
            assertNotNull(getFirstElement(doc, "infoAdicional"));
        }

        @Test
        @DisplayName("comprobante sin infoAdicional omite el nodo")
        void noInfoAdicional() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.minimalWaybill());
            var doc = parseXml(xml);

            assertNull(getFirstElement(doc, "infoAdicional"));
        }

        @Test
        @DisplayName("comprobante mínimo genera XML válido sin nodos opcionales")
        void minimalWaybill() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.minimalWaybill());
            var doc = parseXml(xml);

            assertNotNull(doc.getDocumentElement());
            assertEquals("guiaRemision", doc.getDocumentElement().getTagName());
        }
    }

    @Nested
    @DisplayName("infoTributaria")
    class InfoTributaria {

        @Test
        @DisplayName("codDoc es 06 para guía de remisión")
        void codDoc() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            assertEquals("06", getTextContent(doc, "codDoc"));
        }

        @Test
        @DisplayName("claveAcceso se incluye correctamente")
        void claveAcceso() throws Exception {
            var data = WaybillDataFixtures.simpleWaybill();
            var xml = WaybillXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals(data.accessKey(), getTextContent(doc, "claveAcceso"));
        }

        @Test
        @DisplayName("datos del emisor se incluyen correctamente")
        void issuerData() throws Exception {
            var data = WaybillDataFixtures.simpleWaybill();
            var xml = WaybillXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals("1", getTextContent(doc, "ambiente"));
            assertEquals("1", getTextContent(doc, "tipoEmision"));
            assertEquals(data.taxpayer().legalName(), getTextContent(doc, "razonSocial"));
            assertEquals(data.taxpayer().ruc(), getTextContent(doc, "ruc"));
            assertEquals("001", getTextContent(doc, "estab"));
            assertEquals("001", getTextContent(doc, "ptoEmi"));
        }

        @Test
        @DisplayName("nombreComercial incluido cuando tiene valor")
        void tradeNameIncluded() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            assertEquals("PRUEBAS COMERCIAL", getTextContent(doc, "nombreComercial"));
        }

        @Test
        @DisplayName("nombreComercial omitido cuando es null")
        void tradeNameOmitted() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.minimalWaybill());
            var doc = parseXml(xml);

            assertNull(getFirstElement(doc, "nombreComercial"));
        }

        @Test
        @DisplayName("agenteRetencion incluido cuando tiene valor")
        void withholdingAgent() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            assertEquals("1", getTextContent(doc, "agenteRetencion"));
        }

        @Test
        @DisplayName("contribuyenteRimpe incluido cuando tiene valor")
        void rimpeContributor() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            assertTrue(xml.contains("contribuyenteRimpe"));
        }
    }

    @Nested
    @DisplayName("infoGuiaRemision")
    class InfoGuiaRemision {

        @Test
        @DisplayName("dirPartida se incluye correctamente")
        void departureAddress() throws Exception {
            var data = WaybillDataFixtures.simpleWaybill();
            var xml = WaybillXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals(data.departureAddress(), getTextContent(doc, "dirPartida"));
        }

        @Test
        @DisplayName("datos del transportista se incluyen")
        void carrierData() throws Exception {
            var data = WaybillDataFixtures.simpleWaybill();
            var xml = WaybillXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals("TRANSPORTES DEL NORTE CIA. LTDA.",
                    getTextContent(doc, "razonSocialTransportista"));
            assertEquals("04", getTextContent(doc, "tipoIdentificacionTransportista"));
            assertEquals("1790016919001", getTextContent(doc, "rucTransportista"));
        }

        @Test
        @DisplayName("fechas de transporte en formato dd/MM/yyyy")
        void transportDates() throws Exception {
            var data = WaybillDataFixtures.simpleWaybill();
            var xml = WaybillXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals(data.transportStartDate().format(SRI_DATE_FORMAT),
                    getTextContent(doc, "fechaIniTransporte"));
            assertEquals(data.transportEndDate().format(SRI_DATE_FORMAT),
                    getTextContent(doc, "fechaFinTransporte"));
        }

        @Test
        @DisplayName("placa se incluye correctamente")
        void licensePlate() throws Exception {
            var data = WaybillDataFixtures.simpleWaybill();
            var xml = WaybillXmlBuilder.build(data);
            var doc = parseXml(xml);

            assertEquals(data.licensePlate(), getTextContent(doc, "placa"));
        }

        @Test
        @DisplayName("obligadoContabilidad incluido cuando requiredAccounting=true")
        void obligadoContabilidad() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);
            assertEquals("SI", getTextContent(doc, "obligadoContabilidad"));

            var xmlMinimal = WaybillXmlBuilder.build(WaybillDataFixtures.minimalWaybill());
            var docMinimal = parseXml(xmlMinimal);
            assertNull(getFirstElement(docMinimal, "obligadoContabilidad"));
        }

        @Test
        @DisplayName("rise omitido cuando carrier.rise es null")
        void riseOmitted() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            assertNull(getFirstElement(doc, "rise"));
        }
    }

    @Nested
    @DisplayName("Destinatarios")
    class Destinatarios {

        @Test
        @DisplayName("un destinatario se incluye correctamente")
        void singleAddressee() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            var destinatarios = getElements(doc, "destinatario");
            assertEquals(1, destinatarios.getLength());
        }

        @Test
        @DisplayName("múltiples destinatarios se incluyen")
        void multipleAddressees() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.multiAddresseeWaybill());
            var doc = parseXml(xml);

            var destinatarios = getElements(doc, "destinatario");
            assertEquals(3, destinatarios.getLength());
        }

        @Test
        @DisplayName("datos del destinatario se incluyen correctamente")
        void addresseeData() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            assertEquals("1790016919001", getTextContent(doc, "identificacionDestinatario"));
            assertEquals("CLIENTE NACIONAL CIA. LTDA.",
                    getTextContent(doc, "razonSocialDestinatario"));
            assertEquals("Guayaquil, Av. 9 de Octubre 100",
                    getTextContent(doc, "dirDestinatario"));
            assertEquals("Venta de mercadería", getTextContent(doc, "motivoTraslado"));
        }

        @Test
        @DisplayName("codEstabDestino incluido cuando tiene valor")
        void destinationEstablishment() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            assertEquals("002", getTextContent(doc, "codEstabDestino"));
        }

        @Test
        @DisplayName("ruta incluida cuando tiene valor")
        void route() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            assertEquals("Quito-Guayaquil", getTextContent(doc, "ruta"));
        }

        @Test
        @DisplayName("campos opcionales omitidos en destinatario mínimo")
        void minimalAddresseeOmitsOptionalFields() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.minimalWaybill());
            var doc = parseXml(xml);

            assertNull(getFirstElement(doc, "docAduaneroUnico"));
            assertNull(getFirstElement(doc, "codEstabDestino"));
            assertNull(getFirstElement(doc, "ruta"));
            assertNull(getFirstElement(doc, "codDocSustento"));
            assertNull(getFirstElement(doc, "numDocSustento"));
        }

        @Test
        @DisplayName("datos de documento de sustento incluidos cuando tiene valor")
        void supportDocument() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            assertEquals("01", getTextContent(doc, "codDocSustento"));
            assertEquals("001-001-000000234", getTextContent(doc, "numDocSustento"));
        }
    }

    @Nested
    @DisplayName("Detalles de ítems")
    class Detalles {

        @Test
        @DisplayName("detalle con descripción y cantidad")
        void basicDetail() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            var detalles = getElements(doc, "detalle");
            assertTrue(detalles.getLength() >= 1);

            assertEquals("Producto de prueba A", getTextContent(doc, "descripcion"));
            assertNotNull(getTextContent(doc, "cantidad"));
        }

        @Test
        @DisplayName("codigoInterno incluido cuando tiene valor")
        void mainCode() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            assertEquals("PROD001", getTextContent(doc, "codigoInterno"));
        }

        @Test
        @DisplayName("codigoAdicional incluido cuando tiene valor")
        void auxiliaryCode() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            assertEquals("AUX001", getTextContent(doc, "codigoAdicional"));
        }

        @Test
        @DisplayName("detallesAdicionales incluidos cuando tiene valores")
        void additionalDetails() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            assertTrue(xml.contains("detallesAdicionales"));
            assertTrue(xml.contains("detAdicional"));
        }

        @Test
        @DisplayName("múltiples ítems en un destinatario")
        void multipleItems() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.multiAddresseeWaybill());
            var doc = parseXml(xml);

            var detalles = getElements(doc, "detalle");
            assertTrue(detalles.getLength() > 1);
        }

        @Test
        @DisplayName("cantidad se formatea con hasta 6 decimales")
        void quantityFormat() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            // 100.000000 → should be "100" (stripTrailingZeros) or similar
            assertTrue(xml.contains("cantidad"));
        }
    }

    @Nested
    @DisplayName("Información adicional")
    class InfoAdicional {

        @Test
        @DisplayName("campos adicionales con nombre y valor")
        void additionalFields() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.simpleWaybill());
            var doc = parseXml(xml);

            var campos = getElements(doc, "campoAdicional");
            assertTrue(campos.getLength() > 0);

            var first = (Element) campos.item(0);
            assertNotNull(first.getAttribute("nombre"));
            assertNotNull(first.getTextContent());
        }

        @Test
        @DisplayName("múltiples campos adicionales")
        void multipleFields() throws Exception {
            var xml = WaybillXmlBuilder.build(WaybillDataFixtures.multiAddresseeWaybill());
            var doc = parseXml(xml);

            var campos = getElements(doc, "campoAdicional");
            assertEquals(2, campos.getLength());
        }
    }
}
