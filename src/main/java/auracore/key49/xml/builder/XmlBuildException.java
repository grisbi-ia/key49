package auracore.key49.xml.builder;

/**
 * Excepción lanzada cuando ocurre un error al construir el XML de un comprobante electrónico.
 */
public class XmlBuildException extends RuntimeException {

    public XmlBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
