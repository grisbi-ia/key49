package auracore.key49.sri;

/**
 * Excepción lanzada cuando falla la comunicación con los servicios SOAP del SRI.
 *
 * <p>Encapsula errores de red (timeout, conexión rechazada), parseo de respuestas SOAP,
 * o errores inesperados del servicio.
 */
public class SriException extends RuntimeException {

    public SriException(String message) {
        super(message);
    }

    public SriException(String message, Throwable cause) {
        super(message, cause);
    }
}
