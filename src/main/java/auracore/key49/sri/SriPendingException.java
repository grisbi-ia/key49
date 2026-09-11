package auracore.key49.sri;

/**
 * Excepción que indica que el SRI todavía no tiene disponible la autorización
 * de un comprobante (respuesta sin {@code <autorizacion>} o en procesamiento).
 *
 * <p>No es un fallo definitivo: el comprobante debe reconciliarse más tarde
 * consultando nuevamente el servicio de Autorización.</p>
 */
public class SriPendingException extends SriException {

    public SriPendingException(String message) {
        super(message);
    }

    public SriPendingException(String message, Throwable cause) {
        super(message, cause);
    }
}
