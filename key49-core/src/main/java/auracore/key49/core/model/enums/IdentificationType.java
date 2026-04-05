package auracore.key49.core.model.enums;

import java.util.Arrays;

/**
 * Tipos de identificación según catálogo SRI (Tabla 6).
 */
public enum IdentificationType {

    RUC("04", 13),
    CEDULA("05", 10),
    PASSPORT("06", -1),
    FINAL_CONSUMER("07", 13);

    private final String sriCode;
    private final int length;

    IdentificationType(String sriCode, int length) {
        this.sriCode = sriCode;
        this.length = length;
    }

    public String sriCode() {
        return sriCode;
    }

    /**
     * Longitud esperada del identificador. -1 indica longitud variable.
     */
    public int length() {
        return length;
    }

    public static IdentificationType fromSriCode(String code) {
        return Arrays.stream(values())
                .filter(t -> t.sriCode.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown SRI identification type code: " + code));
    }
}
