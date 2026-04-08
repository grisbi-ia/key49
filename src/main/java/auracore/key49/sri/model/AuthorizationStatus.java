package auracore.key49.sri.model;

/**
 * Estado de la respuesta del servicio de Autorización del SRI.
 */
public enum AuthorizationStatus {

    /** El comprobante fue autorizado por el SRI. */
    AUTORIZADO("AUTORIZADO"),

    /** El comprobante no fue autorizado por el SRI. */
    NO_AUTORIZADO("NO AUTORIZADO");

    private final String value;

    AuthorizationStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static AuthorizationStatus fromValue(String value) {
        for (AuthorizationStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown authorization status: " + value);
    }
}
