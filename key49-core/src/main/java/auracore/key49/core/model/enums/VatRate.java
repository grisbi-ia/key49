package auracore.key49.core.model.enums;

import java.math.BigDecimal;
import java.util.Arrays;

/**
 * Porcentajes de IVA según catálogo SRI (Tabla 17).
 */
public enum VatRate {

    ZERO("0", BigDecimal.ZERO, "0%"),
    TWELVE("2", new BigDecimal("12"), "12%"),
    FOURTEEN("3", new BigDecimal("14"), "14%"),
    FIFTEEN("4", new BigDecimal("15"), "15%"),
    NOT_TAXABLE("6", BigDecimal.ZERO, "No objeto de impuesto"),
    EXEMPT("7", BigDecimal.ZERO, "Exento de IVA"),
    DIFFERENTIATED("8", new BigDecimal("5"), "5%");

    private final String sriCode;
    private final BigDecimal percentage;
    private final String description;

    VatRate(String sriCode, BigDecimal percentage, String description) {
        this.sriCode = sriCode;
        this.percentage = percentage;
        this.description = description;
    }

    public String sriCode() {
        return sriCode;
    }

    public BigDecimal percentage() {
        return percentage;
    }

    public String description() {
        return description;
    }

    public static VatRate fromSriCode(String code) {
        return Arrays.stream(values())
                .filter(v -> v.sriCode.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown SRI VAT rate code: " + code));
    }
}
