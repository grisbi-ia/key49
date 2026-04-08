package auracore.key49.xml.validation;

/**
 * Excepción lanzada cuando no se puede cargar o compilar un esquema XSD.
 */
public class XsdLoadException extends RuntimeException {

    public XsdLoadException(String message) {
        super(message);
    }

    public XsdLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
