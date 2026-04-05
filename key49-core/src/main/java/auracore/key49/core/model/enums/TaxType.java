package auracore.key49.core.model.enums;

import java.util.Arrays;

/**
 * Tipos de impuesto según catálogo SRI (Tabla 16).
 */
public enum TaxType {

    IVA("2", "IVA"),
    ICE("3", "ICE"),
    IRBPNR("5", "IRBPNR");

    private final String sriCode;
    private final String description;

    TaxType(String sriCode, String description) {
        this.sriCode = sriCode;
        this.description = description;
    }

    public String sriCode() {
        return sriCode;
    }

    public String description() {
        return description;
    }

    public static TaxType fromSriCode(String code) {
        return Arrays.stream(values())
                .filter(t -> t.sriCode.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown SRI tax type code: " + code));
    }
}
