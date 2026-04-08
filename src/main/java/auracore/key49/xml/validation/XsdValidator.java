package auracore.key49.xml.validation;

import java.io.IOException;
import java.io.StringReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import auracore.key49.core.model.enums.DocumentType;

/**
 * Validador de XML contra esquemas XSD del SRI.
 *
 * <p>Carga dinámica del XSD según el tipo de documento. Los schemas compilados
 * se cachean en memoria para evitar reparseo en cada validación.
 *
 * <p>Los XSD deben estar en el classpath bajo {@code /xsd/sri/}.
 */
public final class XsdValidator {

    private static final String XSD_BASE_PATH = "/xsd/sri/";

    /**
     * Mapeo de tipo de documento → nombre del archivo XSD.
     */
    private static final Map<DocumentType, String> XSD_FILES = Map.of(
            DocumentType.INVOICE, "factura_V2.1.0.xsd",
            DocumentType.CREDIT_NOTE, "NotaCredito_V1.1.0.xsd",
            DocumentType.DEBIT_NOTE, "NotaDebito_V1.0.0.xsd",
            DocumentType.WAYBILL, "GuiaRemision_V1.1.0.xsd",
            DocumentType.WITHHOLDING, "ComprobanteRetencion_V2.0.0.xsd",
            DocumentType.PURCHASE_CLEARANCE, "LiquidacionCompra_V1.1.0.xsd"
    );

    /**
     * Cache de schemas compilados por tipo de documento.
     */
    private static final ConcurrentHashMap<DocumentType, Schema> SCHEMA_CACHE = new ConcurrentHashMap<>();

    private XsdValidator() {
    }

    /**
     * Valida un XML contra el esquema XSD correspondiente al tipo de documento.
     *
     * @param xml          contenido XML como String
     * @param documentType tipo de documento que determina el XSD a usar
     * @return resultado con indicador de validez y lista de errores
     * @throws IllegalArgumentException si no existe XSD para el tipo de documento
     * @throws XsdLoadException si no se puede cargar o compilar el XSD
     */
    public static XsdValidationResult validate(String xml, DocumentType documentType) {
        var schema = getSchema(documentType);
        var errors = new ArrayList<XsdValidationResult.ValidationError>();

        try {
            var validator = schema.newValidator();
            validator.setErrorHandler(new CollectingErrorHandler(errors));
            validator.validate(new StreamSource(new StringReader(xml)));
        } catch (SAXParseException e) {
            errors.add(toValidationError(e));
        } catch (SAXException e) {
            errors.add(new XsdValidationResult.ValidationError(-1, -1, e.getMessage()));
        } catch (IOException e) {
            errors.add(new XsdValidationResult.ValidationError(-1, -1, "I/O error during validation: " + e.getMessage()));
        }

        if (errors.isEmpty()) {
            return XsdValidationResult.success();
        }
        return XsdValidationResult.failure(errors);
    }

    /**
     * Obtiene el schema compilado, cacheándolo tras la primera carga.
     */
    private static Schema getSchema(DocumentType documentType) {
        return SCHEMA_CACHE.computeIfAbsent(documentType, XsdValidator::loadSchema);
    }

    /**
     * Carga y compila el schema XSD desde el classpath.
     */
    private static Schema loadSchema(DocumentType documentType) {
        var xsdFile = XSD_FILES.get(documentType);
        if (xsdFile == null) {
            throw new IllegalArgumentException("No XSD schema available for document type: " + documentType);
        }

        var xsdPath = XSD_BASE_PATH + xsdFile;
        URL xsdUrl = XsdValidator.class.getResource(xsdPath);
        if (xsdUrl == null) {
            throw new XsdLoadException("XSD file not found on classpath: " + xsdPath);
        }

        try {
            var factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            return factory.newSchema(xsdUrl);
        } catch (SAXException e) {
            throw new XsdLoadException("Failed to compile XSD schema: " + xsdPath, e);
        }
    }

    /**
     * Convierte una SAXParseException en un ValidationError.
     */
    static XsdValidationResult.ValidationError toValidationError(SAXParseException e) {
        return new XsdValidationResult.ValidationError(
                e.getLineNumber(),
                e.getColumnNumber(),
                e.getLocalizedMessage()
        );
    }

    /**
     * Limpia el cache de schemas (útil para testing).
     */
    static void clearSchemaCache() {
        SCHEMA_CACHE.clear();
    }

    /**
     * ErrorHandler que recopila todos los errores en lugar de lanzar excepción
     * al primer error encontrado.
     */
    private static final class CollectingErrorHandler implements org.xml.sax.ErrorHandler {

        private final ArrayList<XsdValidationResult.ValidationError> errors;

        CollectingErrorHandler(ArrayList<XsdValidationResult.ValidationError> errors) {
            this.errors = errors;
        }

        @Override
        public void warning(SAXParseException e) {
            // Warnings no se reportan como errores de validación
        }

        @Override
        public void error(SAXParseException e) {
            errors.add(toValidationError(e));
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXParseException {
            errors.add(toValidationError(e));
            throw e; // Fatal errors detienen la validación
        }
    }
}
