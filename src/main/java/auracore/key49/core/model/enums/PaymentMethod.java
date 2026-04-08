package auracore.key49.core.model.enums;

import java.util.Arrays;

/**
 * Formas de pago según catálogo SRI (Tabla 24).
 */
public enum PaymentMethod {

    SIN_UTILIZACION_DEL_SISTEMA_FINANCIERO("01", "Sin utilización del sistema financiero"),
    COMPENSACION_DE_DEUDAS("15", "Compensación de deudas"),
    TARJETA_DE_DEBITO("16", "Tarjeta de débito"),
    DINERO_ELECTRONICO("17", "Dinero electrónico"),
    TARJETA_PREPAGO("18", "Tarjeta prepago"),
    TARJETA_DE_CREDITO("19", "Tarjeta de crédito"),
    OTROS_CON_UTILIZACION_SISTEMA_FINANCIERO("20", "Otros con utilización del sistema financiero"),
    ENDOSO_DE_TITULOS("21", "Endoso de títulos");

    private final String sriCode;
    private final String description;

    PaymentMethod(String sriCode, String description) {
        this.sriCode = sriCode;
        this.description = description;
    }

    public String sriCode() {
        return sriCode;
    }

    public String description() {
        return description;
    }

    public static PaymentMethod fromSriCode(String code) {
        return Arrays.stream(values())
                .filter(p -> p.sriCode.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown SRI payment method code: " + code));
    }
}
