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
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.SchemaFactory;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import auracore.key49.core.model.enums.DocumentType;
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
 * Helper reutilizable para manipulación de XML en tests de validación XSD.
 * Centraliza operaciones DOM (parse, serialize, removeElement,
 * replaceElementValue) y la generación de XML válidos para cada tipo de
 * comprobante del SRI.
 */
final class XmlTestHelper {

    private static final Map<DocumentType, String> XSD_FILES = Map.of(
            DocumentType.INVOICE, "factura_V2.1.0.xsd",
            DocumentType.CREDIT_NOTE, "NotaCredito_V1.1.0.xsd",
            DocumentType.DEBIT_NOTE, "NotaDebito_V1.0.0.xsd",
            DocumentType.WITHHOLDING, "ComprobanteRetencion_V2.0.0.xsd",
            DocumentType.WAYBILL, "GuiaRemision_V1.1.0.xsd",
            DocumentType.PURCHASE_CLEARANCE, "LiquidacionCompra_V1.1.0.xsd");

    private XmlTestHelper() {
    }

    // ── DOM manipulation ────────────────────────────────────────────────────
    static Document parseXml(String xml) throws SAXException, IOException, ParserConfigurationException {
        var factory = DocumentBuilderFactory.newInstance();
        var builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    static String serialize(Document doc) throws TransformerException {
        var tf = TransformerFactory.newInstance();
        var transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        var sw = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(sw));
        return sw.toString();
    }

    /**
     * Remueve la primera ocurrencia de {@code childTag} dentro del primer
     * elemento {@code parentTag} y retorna el XML serializado.
     */
    static String removeElement(String xml, String parentTag, String childTag) throws Exception {
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

    /**
     * Reemplaza el texto del primer elemento {@code tagName} con
     * {@code newValue} y retorna el XML serializado.
     */
    static String replaceElementValue(String xml, String tagName, String newValue) throws Exception {
        var doc = parseXml(xml);
        var elements = doc.getElementsByTagName(tagName);
        assertTrue(elements.getLength() > 0,
                "Element <%s> must exist in the XML".formatted(tagName));
        elements.item(0).setTextContent(newValue);
        return serialize(doc);
    }

    // ── XSD validation ──────────────────────────────────────────────────────
    static void validateAgainstXsd(String xml, DocumentType type) throws SAXException, IOException {
        var xsdFile = XSD_FILES.get(type);
        assertNotNull(xsdFile, "No XSD mapping for " + type);
        var schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        var xsdUrl = XmlTestHelper.class.getResource("/xsd/sri/" + xsdFile);
        assertNotNull(xsdUrl, "XSD file %s must be on classpath".formatted(xsdFile));
        var schema = schemaFactory.newSchema(xsdUrl);
        var validator = schema.newValidator();
        validator.validate(new StreamSource(new StringReader(xml)));
    }

    static void assertXsdFails(String xml, DocumentType type) {
        assertThrows(SAXException.class, () -> validateAgainstXsd(xml, type),
                "XSD validation should fail for XML with invalid/missing field");
    }

    // ── Valid XML builders ──────────────────────────────────────────────────
    static String buildValidXml(DocumentType type) {
        return switch (type) {
            case INVOICE ->
                buildInvoiceXml();
            case CREDIT_NOTE ->
                buildCreditNoteXml();
            case DEBIT_NOTE ->
                buildDebitNoteXml();
            case WITHHOLDING ->
                buildWithholdingXml();
            case WAYBILL ->
                buildWaybillXml();
            case PURCHASE_CLEARANCE ->
                buildPurchaseClearanceXml();
        };
    }

    private static String buildInvoiceXml() {
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

    private static String buildCreditNoteXml() {
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

    private static String buildDebitNoteXml() {
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

    private static String buildWithholdingXml() {
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

    private static String buildWaybillXml() {
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

    private static String buildPurchaseClearanceXml() {
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
}
