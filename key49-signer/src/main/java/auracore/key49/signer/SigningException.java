package auracore.key49.signer;

/**
 * Excepción lanzada cuando falla la firma digital de un comprobante electrónico.
 *
 * <p>Encapsula errores de carga de certificado, parseo de XML, o ejecución
 * de la firma XAdES-BES.
 */
public class SigningException extends RuntimeException {

    public SigningException(String message) {
        super(message);
    }

    public SigningException(String message, Throwable cause) {
        super(message, cause);
    }
}
