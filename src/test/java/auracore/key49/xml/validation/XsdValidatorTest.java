package auracore.key49.xml.validation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import auracore.key49.core.model.enums.DocumentType;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para XsdValidator.
 */
class XsdValidatorTest {

    @AfterEach
    void clearCache() {
        XsdValidator.clearSchemaCache();
    }

    // ── Helpers ──
    /**
     * Lee un XML de ejemplo del classpath (/xsd/sri/{filename}).
     */
    private String loadSriExampleXml(String filename) {
        var path = "/xsd/sri/" + filename;
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("Example XML not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read example XML: " + path, e);
        }
    }

    // ── Tests de XML válido ──
    @Nested
    @DisplayName("XML válido")
    class ValidXml {

        @Test
        @DisplayName("Factura generada por InvoiceXmlBuilder pasa validación XSD")
        void invoiceFromBuilderIsValid() {
            var xml = XmlTestHelper.buildValidXml(DocumentType.INVOICE);

            var result = XsdValidator.validate(xml, DocumentType.INVOICE);

            assertTrue(result.valid(), "Invoice XML should be valid. Errors: " + result.errors());
            assertTrue(result.errors().isEmpty());
        }

        @Test
        @DisplayName("XML de ejemplo del SRI para factura pasa validación")
        void sriInvoiceExampleIsValid() {
            var xml = loadSriExampleXml("factura_V2.1.0.xml");

            var result = XsdValidator.validate(xml, DocumentType.INVOICE);

            assertTrue(result.valid(), "SRI invoice example should be valid. Errors: " + result.errors());
        }

        @Test
        @DisplayName("XML de ejemplo del SRI para nota de crédito pasa validación")
        void sriCreditNoteExampleIsValid() {
            var xml = loadSriExampleXml("NotaCredito_V1.1.0.xml");

            var result = XsdValidator.validate(xml, DocumentType.CREDIT_NOTE);

            assertTrue(result.valid(), "SRI credit note example should be valid. Errors: " + result.errors());
        }

        @Test
        @DisplayName("XML de ejemplo del SRI para nota de débito pasa validación")
        void sriDebitNoteExampleIsValid() {
            var xml = loadSriExampleXml("NotaDebito_V1.0.0.xml");

            var result = XsdValidator.validate(xml, DocumentType.DEBIT_NOTE);

            assertTrue(result.valid(), "SRI debit note example should be valid. Errors: " + result.errors());
        }

        @Test
        @DisplayName("XML de ejemplo del SRI para guía de remisión pasa validación")
        void sriWaybillExampleIsValid() {
            var xml = loadSriExampleXml("GuiaRemision_V1.1.0.xml");

            var result = XsdValidator.validate(xml, DocumentType.WAYBILL);

            assertTrue(result.valid(), "SRI waybill example should be valid. Errors: " + result.errors());
        }

        @Test
        @DisplayName("XML de ejemplo del SRI para retención pasa validación")
        void sriWithholdingExampleIsValid() {
            var xml = loadSriExampleXml("ComprobanteRetencion_V2.0.0.xml");

            var result = XsdValidator.validate(xml, DocumentType.WITHHOLDING);

            assertTrue(result.valid(), "SRI withholding example should be valid. Errors: " + result.errors());
        }

        @Test
        @DisplayName("XML de ejemplo del SRI para liquidación de compra pasa validación")
        void sriPurchaseClearanceExampleIsValid() {
            var xml = loadSriExampleXml("LiquidacionCompra_V1.1.0.xml");

            var result = XsdValidator.validate(xml, DocumentType.PURCHASE_CLEARANCE);

            assertTrue(result.valid(), "SRI purchase clearance example should be valid. Errors: " + result.errors());
        }
    }

    // ── Tests de XML inválido ──
    @Nested
    @DisplayName("XML inválido")
    class InvalidXml {

        @Test
        @DisplayName("XML mal formado retorna error")
        void malformedXmlReturnsError() {
            var xml = "<factura><infoTributaria><ambiente>1</ambiente>";

            var result = XsdValidator.validate(xml, DocumentType.INVOICE);

            assertFalse(result.valid());
            assertFalse(result.errors().isEmpty(), "Should contain at least one error");
        }

        @Test
        @DisplayName("XML vacío retorna error")
        void emptyXmlReturnsError() {
            var result = XsdValidator.validate("", DocumentType.INVOICE);

            assertFalse(result.valid());
            assertFalse(result.errors().isEmpty());
        }

        @Test
        @DisplayName("XML con elemento raíz incorrecto retorna error")
        void wrongRootElementReturnsError() {
            var xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <notaCredito id="comprobante" version="1.1.0">
                        <infoTributaria>
                            <ambiente>1</ambiente>
                            <tipoEmision>1</tipoEmision>
                            <razonSocial>TEST</razonSocial>
                            <ruc>0000000000001</ruc>
                            <claveAcceso>0000000000000000000000000000000000000000000000000</claveAcceso>
                            <codDoc>04</codDoc>
                            <estab>001</estab>
                            <ptoEmi>001</ptoEmi>
                            <secuencial>000000001</secuencial>
                            <dirMatriz>Quito</dirMatriz>
                        </infoTributaria>
                    </notaCredito>
                    """;

            var result = XsdValidator.validate(xml, DocumentType.INVOICE);

            assertFalse(result.valid(), "Credit note XML should not validate as invoice");
            assertFalse(result.errors().isEmpty());
        }

        @Test
        @DisplayName("XML con campos requeridos faltantes retorna errores")
        void missingRequiredFieldsReturnsErrors() {
            var xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <factura id="comprobante" version="2.1.0">
                        <infoTributaria>
                            <ambiente>1</ambiente>
                            <tipoEmision>1</tipoEmision>
                            <razonSocial>TEST</razonSocial>
                            <ruc>0000000000001</ruc>
                            <claveAcceso>0000000000000000000000000000000000000000000000000</claveAcceso>
                            <codDoc>01</codDoc>
                            <estab>001</estab>
                            <ptoEmi>001</ptoEmi>
                            <secuencial>000000001</secuencial>
                            <dirMatriz>Quito</dirMatriz>
                        </infoTributaria>
                    </factura>
                    """;

            var result = XsdValidator.validate(xml, DocumentType.INVOICE);

            assertFalse(result.valid(), "XML missing infoFactura & detalles should be invalid");
            assertTrue(result.errors().size() >= 1, "Should have at least one error for missing elements");
        }

        @Test
        @DisplayName("Múltiples errores se recopilan (no solo el primero)")
        void multipleErrorsAreCollected() {
            // Factura con infoFactura presente pero con contenido inválido en
            // múltiples campos — genera errores de validación en distintos puntos.
            var xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <factura id="comprobante" version="2.1.0">
                        <infoTributaria>
                            <ambiente>1</ambiente>
                            <tipoEmision>1</tipoEmision>
                            <razonSocial>TEST</razonSocial>
                            <ruc>0000000000001</ruc>
                            <claveAcceso>0000000000000000000000000000000000000000000000000</claveAcceso>
                            <codDoc>01</codDoc>
                            <estab>001</estab>
                            <ptoEmi>001</ptoEmi>
                            <secuencial>000000001</secuencial>
                            <dirMatriz>Quito</dirMatriz>
                        </infoTributaria>
                        <infoFactura>
                            <fechaEmision>01/01/2024</fechaEmision>
                            <obligadoContabilidad>SI</obligadoContabilidad>
                            <tipoIdentificacionComprador>04</tipoIdentificacionComprador>
                            <razonSocialComprador>COMPRADOR</razonSocialComprador>
                            <identificacionComprador>1790567890001</identificacionComprador>
                            <totalSinImpuestos>INVALIDO</totalSinImpuestos>
                            <totalDescuento>INVALIDO</totalDescuento>
                            <totalConImpuestos>
                                <totalImpuesto>
                                    <codigo>2</codigo>
                                    <codigoPorcentaje>0</codigoPorcentaje>
                                    <baseImponible>INVALIDO</baseImponible>
                                    <valor>INVALIDO</valor>
                                </totalImpuesto>
                            </totalConImpuestos>
                            <propina>0.00</propina>
                            <importeTotal>INVALIDO</importeTotal>
                            <moneda>DOLAR</moneda>
                            <pagos>
                                <pago>
                                    <formaPago>20</formaPago>
                                    <total>INVALIDO</total>
                                </pago>
                            </pagos>
                        </infoFactura>
                        <detalles>
                            <detalle>
                                <descripcion>Producto</descripcion>
                                <cantidad>INVALIDO</cantidad>
                                <precioUnitario>INVALIDO</precioUnitario>
                                <descuento>0.00</descuento>
                                <precioTotalSinImpuesto>INVALIDO</precioTotalSinImpuesto>
                                <impuestos>
                                    <impuesto>
                                        <codigo>2</codigo>
                                        <codigoPorcentaje>0</codigoPorcentaje>
                                        <tarifa>0</tarifa>
                                        <baseImponible>INVALIDO</baseImponible>
                                        <valor>INVALIDO</valor>
                                    </impuesto>
                                </impuestos>
                            </detalle>
                        </detalles>
                    </factura>
                    """;

            var result = XsdValidator.validate(xml, DocumentType.INVOICE);

            assertFalse(result.valid());
            assertTrue(result.errors().size() > 1,
                    "Should collect multiple errors, got %d: %s".formatted(result.errors().size(), result.errors()));
        }
    }

    // ── Tests de XsdValidationResult ──
    @Nested
    @DisplayName("XsdValidationResult")
    class ResultRecord {

        @Test
        @DisplayName("success() retorna resultado válido sin errores")
        void successReturnsValidResult() {
            var result = XsdValidationResult.success();

            assertTrue(result.valid());
            assertTrue(result.errors().isEmpty());
        }

        @Test
        @DisplayName("failure() retorna resultado inválido con errores")
        void failureReturnsInvalidResult() {
            var errors = java.util.List.of(
                    new XsdValidationResult.ValidationError(5, 10, "Element not allowed"),
                    new XsdValidationResult.ValidationError(12, 3, "Missing required element")
            );

            var result = XsdValidationResult.failure(errors);

            assertFalse(result.valid());
            assertEquals(2, result.errors().size());
        }

        @Test
        @DisplayName("failure() retorna lista inmutable")
        void failureReturnsImmutableList() {
            var errors = new java.util.ArrayList<XsdValidationResult.ValidationError>();
            errors.add(new XsdValidationResult.ValidationError(1, 1, "Error"));

            var result = XsdValidationResult.failure(errors);

            assertThrows(UnsupportedOperationException.class, ()
                    -> result.errors().add(new XsdValidationResult.ValidationError(2, 2, "Another"))
            );
        }

        @Test
        @DisplayName("ValidationError.toString() con línea y columna")
        void validationErrorToStringWithLineAndColumn() {
            var error = new XsdValidationResult.ValidationError(5, 10, "Element not allowed");

            assertEquals("Line 5, Column 10: Element not allowed", error.toString());
        }

        @Test
        @DisplayName("ValidationError.toString() sin línea ni columna")
        void validationErrorToStringWithoutLineAndColumn() {
            var error = new XsdValidationResult.ValidationError(-1, -1, "General error");

            assertEquals("General error", error.toString());
        }
    }

    // ── Tests de carga dinámica de esquemas ──
    @Nested
    @DisplayName("Carga dinámica de esquemas")
    class SchemaLoading {

        @ParameterizedTest(name = "{0}")
        @EnumSource(DocumentType.class)
        @DisplayName("Schema se carga correctamente para todos los tipos de documento")
        void schemaLoadsForAllDocumentTypes(DocumentType documentType) {
            // Verificar que el schema se carga sin excepción
            // Usamos un XML mínimo inválido — lo importante es que no lance XsdLoadException
            var result = XsdValidator.validate("<dummy/>", documentType);

            assertFalse(result.valid(), "Dummy XML should not validate");
            assertFalse(result.errors().isEmpty());
        }
    }

    // ── Tests de cache ──
    @Nested
    @DisplayName("Cache de schemas")
    class SchemaCache {

        @Test
        @DisplayName("Validar dos veces el mismo tipo usa cache (no lanza excepción)")
        void secondValidationUsesCache() {
            var xml = XmlTestHelper.buildValidXml(DocumentType.INVOICE);

            var result1 = XsdValidator.validate(xml, DocumentType.INVOICE);
            var result2 = XsdValidator.validate(xml, DocumentType.INVOICE);

            assertTrue(result1.valid());
            assertTrue(result2.valid());
        }

        @Test
        @DisplayName("clearSchemaCache() permite recarga del schema")
        void clearCacheAllowsReload() {
            var xml = XmlTestHelper.buildValidXml(DocumentType.INVOICE);

            var result1 = XsdValidator.validate(xml, DocumentType.INVOICE);
            XsdValidator.clearSchemaCache();
            var result2 = XsdValidator.validate(xml, DocumentType.INVOICE);

            assertTrue(result1.valid());
            assertTrue(result2.valid());
        }
    }

    // ── Tests negativos para todos los tipos de documento ──
    @Nested
    @DisplayName("XML inválido — todos los tipos de documento")
    class InvalidXmlAllTypes {

        private record DocMeta(String rootElement, String version, String mainInfoNode) {

        }

        private static final java.util.Map<DocumentType, DocMeta> DOC_META = java.util.Map.of(
                DocumentType.INVOICE, new DocMeta("factura", "2.1.0", "infoFactura"),
                DocumentType.CREDIT_NOTE, new DocMeta("notaCredito", "1.1.0", "infoNotaCredito"),
                DocumentType.DEBIT_NOTE, new DocMeta("notaDebito", "1.0.0", "infoNotaDebito"),
                DocumentType.WITHHOLDING, new DocMeta("comprobanteRetencion", "2.0.0", "infoCompRetencion"),
                DocumentType.WAYBILL, new DocMeta("guiaRemision", "1.1.0", "infoGuiaRemision"),
                DocumentType.PURCHASE_CLEARANCE, new DocMeta("liquidacionCompra", "1.1.0", "infoLiquidacionCompra")
        );

        private String buildXmlWithInfoTributariaOnly(DocumentType type) {
            var meta = DOC_META.get(type);
            return """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <%s id="comprobante" version="%s">
                        <infoTributaria>
                            <ambiente>1</ambiente>
                            <tipoEmision>1</tipoEmision>
                            <razonSocial>EMPRESA DEMO S.A.</razonSocial>
                            <ruc>1790012345001</ruc>
                            <claveAcceso>0404202601179001234500110010010000000421234567817</claveAcceso>
                            <codDoc>01</codDoc>
                            <estab>001</estab>
                            <ptoEmi>001</ptoEmi>
                            <secuencial>000000042</secuencial>
                            <dirMatriz>Quito</dirMatriz>
                        </infoTributaria>
                    </%s>
                    """.formatted(meta.rootElement, meta.version, meta.rootElement);
        }

        @ParameterizedTest(name = "{0}: XML mal formado falla")
        @EnumSource(DocumentType.class)
        @DisplayName("XML mal formado retorna error para cada tipo")
        void malformedXmlFails(DocumentType type) {
            var meta = DOC_META.get(type);
            var xml = "<%s><infoTributaria><ambiente>1</ambiente>".formatted(meta.rootElement);

            var result = XsdValidator.validate(xml, type);

            assertFalse(result.valid());
            assertFalse(result.errors().isEmpty());
        }

        @ParameterizedTest(name = "{0}: XML vacío falla")
        @EnumSource(DocumentType.class)
        @DisplayName("XML vacío retorna error para cada tipo")
        void emptyXmlFails(DocumentType type) {
            var result = XsdValidator.validate("", type);

            assertFalse(result.valid());
            assertFalse(result.errors().isEmpty());
        }

        @ParameterizedTest(name = "{0}: elemento raíz incorrecto falla")
        @EnumSource(DocumentType.class)
        @DisplayName("Elemento raíz incorrecto retorna error para cada tipo")
        void wrongRootElementFails(DocumentType type) {
            var xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <elementoIncorrecto>
                        <infoTributaria>
                            <ambiente>1</ambiente>
                        </infoTributaria>
                    </elementoIncorrecto>
                    """;

            var result = XsdValidator.validate(xml, type);

            assertFalse(result.valid());
            assertFalse(result.errors().isEmpty());
        }

        @ParameterizedTest(name = "{0}: nodo principal ausente falla con mensaje descriptivo")
        @EnumSource(DocumentType.class)
        @DisplayName("Nodo principal ausente retorna error descriptivo para cada tipo")
        void missingMainInfoNodeFails(DocumentType type) {
            var meta = DOC_META.get(type);
            var xml = buildXmlWithInfoTributariaOnly(type);

            var result = XsdValidator.validate(xml, type);

            assertFalse(result.valid(),
                    "XML sin <%s> debería fallar para %s".formatted(meta.mainInfoNode, type));
            assertFalse(result.errors().isEmpty(),
                    "Debería contener al menos un error descriptivo");
            var allErrors = String.join(" ", result.errors().stream()
                    .map(XsdValidationResult.ValidationError::message).toList());
            assertFalse(allErrors.isBlank(), "Error messages should not be blank");
        }
    }

    // ── Tests de tipo de documento cruzado ──
    @Nested
    @DisplayName("Validación cruzada de tipos")
    class CrossTypeValidation {

        @Test
        @DisplayName("XML de factura no pasa como nota de crédito")
        void invoiceXmlDoesNotValidateAsCreditNote() {
            var xml = XmlTestHelper.buildValidXml(DocumentType.INVOICE);

            var result = XsdValidator.validate(xml, DocumentType.CREDIT_NOTE);

            assertFalse(result.valid(), "Invoice XML should not validate as credit note");
        }

        @Test
        @DisplayName("XML de nota de crédito no pasa como factura")
        void creditNoteXmlDoesNotValidateAsInvoice() {
            var xml = loadSriExampleXml("NotaCredito_V1.1.0.xml");

            var result = XsdValidator.validate(xml, DocumentType.INVOICE);

            assertFalse(result.valid(), "Credit note XML should not validate as invoice");
        }
    }
}
