package auracore.key49.core.model.enums;

import java.util.Arrays;

/**
 * Tipos de comprobante electrónico según ficha técnica SRI.
 */
public enum DocumentType {

    INVOICE("01", "Factura"),
    PURCHASE_CLEARANCE("03", "Liquidación de Compra"),
    CREDIT_NOTE("04", "Nota de Crédito"),
    DEBIT_NOTE("05", "Nota de Débito"),
    WAYBILL("06", "Guía de Remisión"),
    WITHHOLDING("07", "Comprobante de Retención");

    private final String sriCode;
    private final String description;

    DocumentType(String sriCode, String description) {
        this.sriCode = sriCode;
        this.description = description;
    }

    public String sriCode() {
        return sriCode;
    }

    public String description() {
        return description;
    }

    public static DocumentType fromSriCode(String code) {
        return Arrays.stream(values())
                .filter(t -> t.sriCode.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown SRI document type code: " + code));
    }
}
