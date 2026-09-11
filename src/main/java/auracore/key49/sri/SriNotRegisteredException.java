package auracore.key49.sri;

/**
 * El SRI no tiene registrada la clave de acceso en el servicio de autorización
 * ({@code numeroComprobantes=0}). Es un estado <b>permanente</b>: reintentar no
 * cambia el resultado. Corresponde a "NO REGISTRADA" (el contador debe
 * investigar posible anulación o emisión no autorizada).
 */
public class SriNotRegisteredException extends SriException {

    public SriNotRegisteredException(String message) {
        super(message);
    }
}
