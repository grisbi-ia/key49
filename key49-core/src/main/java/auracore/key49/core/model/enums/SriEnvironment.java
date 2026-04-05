package auracore.key49.core.model.enums;

import java.util.Arrays;

/**
 * Ambiente del SRI para emisión de comprobantes electrónicos.
 */
public enum SriEnvironment {

    TEST("1", "Pruebas"),
    PRODUCTION("2", "Producción");

    private final String sriCode;
    private final String description;

    SriEnvironment(String sriCode, String description) {
        this.sriCode = sriCode;
        this.description = description;
    }

    public String sriCode() {
        return sriCode;
    }

    public String description() {
        return description;
    }

    public static SriEnvironment fromSriCode(String code) {
        return Arrays.stream(values())
                .filter(e -> e.sriCode.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown SRI environment code: " + code));
    }
}
