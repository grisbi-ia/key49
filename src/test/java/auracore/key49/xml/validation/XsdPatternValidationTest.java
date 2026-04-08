package auracore.key49.xml.validation;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import auracore.key49.xml.builder.CreditNoteData;
import auracore.key49.xml.builder.CreditNoteXmlBuilder;
import auracore.key49.xml.builder.DebitNoteData;
import auracore.key49.xml.builder.DebitNoteXmlBuilder;
import auracore.key49.xml.builder.InvoiceData;
import auracore.key49.xml.builder.InvoiceXmlBuilder;
import auracore.key49.xml.builder.PurchaseClearanceData;
import auracore.key49.xml.builder.PurchaseClearanceXmlBuilder;
import auracore.key49.xml.builder.WaybillData;
import auracore.key49.xml.builder.WaybillXmlBuilder;
import auracore.key49.xml.builder.WithholdingData;
import auracore.key49.xml.builder.WithholdingXmlBuilder;

/**
 * Tests negativos que verifican que los XSD del SRI rechazan valores que
 * no cumplen los patterns (restricciones regex) definidos en infoTributaria.
 * Se prueban todos los tipos de comprobante para cada pattern.
 */
class XsdPatternValidationTest {

    // ── Tipos de documento con su XSD ────────────────────────────────────────
    enum DocType {
        INVOICE("factura_V2.1.0.xsd"),
        CREDIT_NOTE("NotaCredito_V1.1.0.xsd"),
        DEBIT_NOTE("NotaDebito_V1.0.0.xsd"),
        WITHHOLDING("ComprobanteRetencion_V2.0.0.xsd"),
        WAYBILL("GuiaRemision_V1.1.0.xsd"),
        PURCHASE_CLEARANCE("LiquidacionCompra_V1.1.0.xsd");

        final String xsdFile;

        DocType(String xsdFile) {
            this.xsdFile = xsdFile;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private String buildValidXml(DocType type) {
        return switch (type) {
            case INVOICE -> buildInvoiceXml();
            case CREDIT_NOTE -> buildCreditNoteXml();
            case DEBIT_NOTE -> buildDebitNoteXml();
            case WITHHOLDING -> buildWithholdingXml();
            case WAYBILL -> buildWaybillXml();
            case PURCHASE_CLEARANCE -> buildPurchaseClearanceXml();
        };
    }

    private String buildInvoiceXml() {
        var taxpayer = new InvoiceData.TaxpayerInfo(
                "1", "1", "EMPRESA DEMO S.A.", "DEMO",
                "1790012345001", "Quito, Av. Principal 123",
                "Sucursal Norte", true, null, null,
                "CONTRIBUYENTE RÉGIMEN RIMPE");
        var recipient = new InvoiceData.Recipient(
                "04", "1790567890001",
                "CLIENTE PRUEBA CIA. LTDA.", "Guayaquil");
        var tax = new InvoiceData.Tax("2", "4",
                new BigDecimal("15.00"), new BigDecimal("50.00"), new BigDecimal("7.50"));
        var item = new InvoiceData.Item("PROD-001", null,
                "Servicio de hosting", "UNIDAD", BigDecimal.ONE,
                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("50.00"),
                List.of(tax));
        var totalTax = new InvoiceData.TotalTax("2", "4", null,
                new BigDecimal("50.00"), new BigDecimal("15.00"), new BigDecimal("7.50"));
        var payment = new InvoiceData.Payment("20", new BigDecimal("57.50"), 0, "dias");
        return InvoiceXmlBuilder.build(new InvoiceData(taxpayer,
                "0404202601179001234500110010010000000421234567817",
                "001", "001", "000000042", LocalDate.of(2025, 4, 15),
                recipient, List.of(item), List.of(totalTax), List.of(payment),
                new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("57.50"), "DOLAR", Map.of()));
    }

    private String buildCreditNoteXml() {
        var taxpayer = new CreditNoteData.TaxpayerInfo(
                "1", "1", "EMPRESA DEMO S.A.", "DEMO",
                "1790012345001", "Quito, Av. Principal 123",
                "Sucursal Norte", true, null, null,
                "CONTRIBUYENTE RÉGIMEN RIMPE");
        var recipient = new CreditNoteData.Recipient(
                "04", "1790567890001", "CLIENTE PRUEBA CIA. LTDA.");
        var tax = new CreditNoteData.Tax("2", "4",
                new BigDecimal("15.00"), new BigDecimal("50.00"), new BigDecimal("7.50"));
        var item = new CreditNoteData.Item("PROD-001", null,
                "Devolución producto", BigDecimal.ONE,
                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("50.00"),
                List.of(tax));
        var totalTax = new CreditNoteData.TotalTax("2", "4",
                new BigDecimal("50.00"), new BigDecimal("7.50"));
        return CreditNoteXmlBuilder.build(new CreditNoteData(taxpayer,
                "0404202604179001234500110010010000000421234567817",
                "001", "001", "000000042", LocalDate.of(2025, 4, 15),
                recipient, "01", "001-001-000000001", LocalDate.of(2025, 4, 10),
                "Devolución", List.of(item), List.of(totalTax),
                new BigDecimal("50.00"), new BigDecimal("57.50"), "DOLAR",
                Map.of()));
    }

    private String buildDebitNoteXml() {
        var taxpayer = new DebitNoteData.TaxpayerInfo(
                "1", "1", "EMPRESA DEMO S.A.", "DEMO",
                "1790012345001", "Quito, Av. Principal 123",
                "Sucursal Norte", true, null, null,
                "CONTRIBUYENTE RÉGIMEN RIMPE");
        var recipient = new DebitNoteData.Recipient(
                "04", "1790567890001", "CLIENTE PRUEBA CIA. LTDA.");
        var tax = new DebitNoteData.Tax("2", "4",
                new BigDecimal("15.00"), new BigDecimal("50.00"), new BigDecimal("7.50"));
        var payment = new DebitNoteData.Payment("01", new BigDecimal("57.50"), 30, "dias");
        var reason = new DebitNoteData.Reason("Intereses por mora", new BigDecimal("50.00"));
        return DebitNoteXmlBuilder.build(new DebitNoteData(taxpayer,
                "0404202605179001234500110010010000000421234567817",
                "001", "001", "000000042", LocalDate.of(2025, 4, 15),
                recipient, "01", "001-001-000000001", LocalDate.of(2025, 4, 10),
                new BigDecimal("50.00"), List.of(tax), new BigDecimal("57.50"),
                List.of(payment), List.of(reason), Map.of()));
    }

    private String buildWithholdingXml() {
        var taxpayer = new WithholdingData.TaxpayerInfo(
                "1", "1", "EMPRESA DEMO S.A.", "DEMO",
                "1790012345001", "Quito, Av. Principal 123",
                "Sucursal Norte", true, null, null,
                "CONTRIBUYENTE RÉGIMEN RIMPE");
        var subject = new WithholdingData.Subject(
                "04", "1790567890001", "PROVEEDOR CIA. LTDA.", "01");
        var docTax = new WithholdingData.SupportingDocTax(
                "2", "4", new BigDecimal("1000.00"),
                new BigDecimal("15.00"), new BigDecimal("150.00"));
        var retention = new WithholdingData.WithholdingLine(
                "1", "303", new BigDecimal("1000.00"),
                new BigDecimal("10.00"), new BigDecimal("100.00"));
        var payment = new WithholdingData.Payment("20", new BigDecimal("1150.00"));
        var supportDoc = new WithholdingData.SupportingDocument(
                "01", "01", "001001000000234",
                LocalDate.of(2025, 3, 15), null,
                "1503202501179214673900110010010000002340000002341",
                "01", null, null, null, null, null,
                new BigDecimal("1000.00"), new BigDecimal("1150.00"),
                List.of(docTax), List.of(retention), List.of(payment));
        return WithholdingXmlBuilder.build(new WithholdingData(taxpayer,
                "0404202607179001234500110010010000000421234567817",
                "001", "001", "000000042", LocalDate.of(2025, 4, 15),
                subject, "03/2025", false, List.of(supportDoc), Map.of()));
    }

    private String buildWaybillXml() {
        var taxpayer = new WaybillData.TaxpayerInfo(
                "1", "1", "EMPRESA DEMO S.A.", "DEMO",
                "1790012345001", "Quito, Av. Principal 123",
                "Sucursal Norte", true, null, null,
                "CONTRIBUYENTE RÉGIMEN RIMPE");
        var carrier = new WaybillData.Carrier("04", "1790016919001",
                "TRANSPORTES CIA. LTDA.", null);
        var item = new WaybillData.Item("PROD001", null, "Producto A",
                new BigDecimal("100.000000"), List.of());
        var addressee = new WaybillData.Addressee(
                "1790016919001", "CLIENTE CIA. LTDA.",
                "Guayaquil, Av. 9 de Octubre", "Venta de mercadería",
                null, "002", null, null, null, null, null, List.of(item));
        return WaybillXmlBuilder.build(new WaybillData(taxpayer,
                "0404202606179001234500110010010000000421234567817",
                "001", "001", "000000042", LocalDate.of(2025, 4, 15),
                "Quito, Bodega Central", carrier,
                LocalDate.of(2025, 4, 15), LocalDate.of(2025, 4, 16),
                "PBB-1234", List.of(addressee), Map.of()));
    }

    private String buildPurchaseClearanceXml() {
        var taxpayer = new PurchaseClearanceData.TaxpayerInfo(
                "1", "1", "EMPRESA DEMO S.A.", "DEMO",
                "1790012345001", "Quito, Av. Principal 123",
                "Sucursal Norte", true, null, null,
                "CONTRIBUYENTE RÉGIMEN RIMPE");
        var supplier = new PurchaseClearanceData.Supplier(
                "05", "1710034065", "PROVEEDOR RURAL", "Santo Domingo");
        var itemTax = new PurchaseClearanceData.Tax(
                "2", "4", new BigDecimal("15.00"),
                new BigDecimal("50.00"), new BigDecimal("7.50"));
        var item = new PurchaseClearanceData.Item("PROD-001", null,
                "Cacao en grano", "QUINTAL", BigDecimal.ONE,
                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("50.00"),
                List.of(itemTax));
        var totalTax = new PurchaseClearanceData.TotalTax("2", "4", null,
                new BigDecimal("50.00"), new BigDecimal("15.00"), new BigDecimal("7.50"));
        var payment = new PurchaseClearanceData.Payment(
                "20", new BigDecimal("57.50"), 0, "dias");
        return PurchaseClearanceXmlBuilder.build(new PurchaseClearanceData(taxpayer,
                "0404202603179001234500110010010000000421234567817",
                "001", "001", "000000042", LocalDate.of(2025, 4, 15),
                supplier, List.of(item), List.of(totalTax), List.of(payment),
                new BigDecimal("50.00"), BigDecimal.ZERO, new BigDecimal("57.50"),
                "DOLAR", Map.of()));
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

    private String replaceElementValue(String xml, String tagName, String newValue) throws Exception {
        var doc = parseXml(xml);
        var elements = doc.getElementsByTagName(tagName);
        assertTrue(elements.getLength() > 0,
                "Element <%s> must exist in the XML".formatted(tagName));
        elements.item(0).setTextContent(newValue);
        return serialize(doc);
    }

    private void assertXsdFails(String xml, DocType type) {
        assertThrows(SAXException.class, () -> validateAgainstXsd(xml, type),
                "XSD validation should fail for XML with invalid pattern value");
    }

    private void validateAgainstXsd(String xml, DocType type) throws SAXException, IOException {
        var schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        var xsdUrl = getClass().getResource("/xsd/sri/" + type.xsdFile);
        assertNotNull(xsdUrl, "XSD file %s must be on classpath".formatted(type.xsdFile));
        var schema = schemaFactory.newSchema(xsdUrl);
        var validator = schema.newValidator();
        validator.validate(new StreamSource(new StringReader(xml)));
    }

    // ── Sanity check ────────────────────────────────────────────────────────
    @ParameterizedTest(name = "{0}")
    @EnumSource(DocType.class)
    @DisplayName("XML base válido pasa XSD para cada tipo de comprobante")
    void baseXmlIsValid(DocType type) {
        assertDoesNotThrow(() -> validateAgainstXsd(buildValidXml(type), type));
    }

    // ── RUC inválido ────────────────────────────────────────────────────────
    @Nested
    @DisplayName("RUC inválido rechazado por XSD")
    class RucInvalido {

        @ParameterizedTest(name = "{0}: RUC con letras")
        @EnumSource(DocType.class)
        @DisplayName("RUC con letras falla XSD")
        void rucWithLetters(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "ruc", "ABC0012345001");
            assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: RUC demasiado corto")
        @EnumSource(DocType.class)
        @DisplayName("RUC con menos de 13 dígitos falla XSD")
        void rucTooShort(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "ruc", "179001234");
            assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: RUC sin sufijo 001")
        @EnumSource(DocType.class)
        @DisplayName("RUC sin sufijo 001 falla XSD")
        void rucWithoutSuffix001(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "ruc", "1790012345999");
            assertXsdFails(xml, type);
        }
    }

    // ── Establecimiento inválido ────────────────────────────────────────────
    @Nested
    @DisplayName("Establecimiento inválido rechazado por XSD")
    class EstablecimientoInvalido {

        @ParameterizedTest(name = "{0}: establecimiento con letras")
        @EnumSource(DocType.class)
        @DisplayName("Establecimiento con letras falla XSD")
        void estabWithLetters(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "estab", "A01");
            assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: establecimiento con más de 3 dígitos")
        @EnumSource(DocType.class)
        @DisplayName("Establecimiento con más de 3 dígitos falla XSD")
        void estabTooLong(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "estab", "0012");
            assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: establecimiento con menos de 3 dígitos")
        @EnumSource(DocType.class)
        @DisplayName("Establecimiento con menos de 3 dígitos falla XSD")
        void estabTooShort(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "estab", "01");
            assertXsdFails(xml, type);
        }
    }

    // ── Punto de emisión inválido ───────────────────────────────────────────
    @Nested
    @DisplayName("Punto de emisión inválido rechazado por XSD")
    class PuntoEmisionInvalido {

        @ParameterizedTest(name = "{0}: ptoEmi con letras")
        @EnumSource(DocType.class)
        @DisplayName("Punto de emisión con letras falla XSD")
        void ptoEmiWithLetters(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "ptoEmi", "A01");
            assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: ptoEmi con más de 3 dígitos")
        @EnumSource(DocType.class)
        @DisplayName("Punto de emisión con más de 3 dígitos falla XSD")
        void ptoEmiTooLong(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "ptoEmi", "0012");
            assertXsdFails(xml, type);
        }
    }

    // ── Secuencial inválido ─────────────────────────────────────────────────
    @Nested
    @DisplayName("Secuencial inválido rechazado por XSD")
    class SecuencialInvalido {

        @ParameterizedTest(name = "{0}: secuencial con letras")
        @EnumSource(DocType.class)
        @DisplayName("Secuencial con letras falla XSD")
        void secuencialWithLetters(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "secuencial", "00000A042");
            assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: secuencial demasiado corto")
        @EnumSource(DocType.class)
        @DisplayName("Secuencial con menos de 9 dígitos falla XSD")
        void secuencialTooShort(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "secuencial", "00042");
            assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: secuencial demasiado largo")
        @EnumSource(DocType.class)
        @DisplayName("Secuencial con más de 9 dígitos falla XSD")
        void secuencialTooLong(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "secuencial", "0000000421");
            assertXsdFails(xml, type);
        }
    }

    // ── Clave de acceso inválida ────────────────────────────────────────────
    @Nested
    @DisplayName("Clave de acceso inválida rechazada por XSD")
    class ClaveAccesoInvalida {

        @ParameterizedTest(name = "{0}: clave de acceso con letras")
        @EnumSource(DocType.class)
        @DisplayName("Clave de acceso con letras falla XSD")
        void claveAccesoWithLetters(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "claveAcceso",
                    "040420260117900123450011001001000000042123456781A");
            assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: clave de acceso demasiado corta")
        @EnumSource(DocType.class)
        @DisplayName("Clave de acceso con menos de 49 dígitos falla XSD")
        void claveAccesoTooShort(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "claveAcceso",
                    "12345678901234567890");
            assertXsdFails(xml, type);
        }
    }

    // ── Código de documento inválido ────────────────────────────────────────
    @Nested
    @DisplayName("Código de documento inválido rechazado por XSD")
    class CodDocInvalido {

        @ParameterizedTest(name = "{0}: codDoc con letras")
        @EnumSource(DocType.class)
        @DisplayName("Código de documento con letras falla XSD")
        void codDocWithLetters(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "codDoc", "AB");
            assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: codDoc con más de 2 dígitos")
        @EnumSource(DocType.class)
        @DisplayName("Código de documento con más de 2 dígitos falla XSD")
        void codDocTooLong(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "codDoc", "012");
            assertXsdFails(xml, type);
        }
    }

    // ── Ambiente inválido ───────────────────────────────────────────────────
    @Nested
    @DisplayName("Ambiente inválido rechazado por XSD")
    class AmbienteInvalido {

        @ParameterizedTest(name = "{0}: ambiente con valor 3")
        @EnumSource(DocType.class)
        @DisplayName("Ambiente con valor fuera de rango (3) falla XSD")
        void ambienteOutOfRange(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "ambiente", "3");
            assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: ambiente con valor 0")
        @EnumSource(DocType.class)
        @DisplayName("Ambiente con valor 0 falla XSD")
        void ambienteZero(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "ambiente", "0");
            assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: ambiente con letras")
        @EnumSource(DocType.class)
        @DisplayName("Ambiente con letras falla XSD")
        void ambienteWithLetters(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "ambiente", "A");
            assertXsdFails(xml, type);
        }
    }

    // ── Tipo de emisión inválido ────────────────────────────────────────────
    @Nested
    @DisplayName("Tipo de emisión inválido rechazado por XSD")
    class TipoEmisionInvalido {

        @ParameterizedTest(name = "{0}: tipoEmision con valor 3")
        @EnumSource(DocType.class)
        @DisplayName("Tipo de emisión con valor fuera de rango (3) falla XSD")
        void tipoEmisionOutOfRange(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "tipoEmision", "3");
            assertXsdFails(xml, type);
        }

        @ParameterizedTest(name = "{0}: tipoEmision con valor 0")
        @EnumSource(DocType.class)
        @DisplayName("Tipo de emisión con valor 0 falla XSD")
        void tipoEmisionZero(DocType type) throws Exception {
            var xml = replaceElementValue(buildValidXml(type), "tipoEmision", "0");
            assertXsdFails(xml, type);
        }
    }
}
