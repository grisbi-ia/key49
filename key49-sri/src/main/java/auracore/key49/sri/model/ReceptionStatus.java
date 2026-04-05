package auracore.key49.sri.model;

/**
 * Estado de la respuesta del servicio de Recepción del SRI.
 */
public enum ReceptionStatus {

    /** El comprobante fue recibido y está pendiente de autorización. */
    RECIBIDA("RECIBIDA"),

    /** El comprobante fue devuelto (rechazado) por errores de validación. */
    DEVUELTA("DEVUELTA");

    private final String value;

    ReceptionStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ReceptionStatus fromValue(String value) {
        for (ReceptionStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown reception status: " + value);
    }
}
