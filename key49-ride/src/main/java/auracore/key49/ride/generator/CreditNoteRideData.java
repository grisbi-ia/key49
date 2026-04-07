package auracore.key49.ride.generator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Datos necesarios para generar el RIDE (PDF) de una nota de crédito. Incluye
 * datos del documento modificado que no aplican a facturas.
 */
public record CreditNoteRideData(
        RideData.Issuer issuer,
        String accessKey,
        String authorizationNumber,
        LocalDateTime authorizationDate,
        String environment,
        String emissionType,
        String establishment,
        String issuePoint,
        String sequenceNumber,
        LocalDate issueDate,
        RideData.Recipient recipient,
        String modifiedDocumentCode,
        String modifiedDocumentNumber,
        LocalDate modifiedDocumentDate,
        String reason,
        List<Item> items,
        List<RideData.TotalTax> totalTaxes,
        BigDecimal subtotalBeforeTax,
        BigDecimal modificationValue,
        String currency,
        Map<String, String> additionalInfo,
        boolean authorized,
        byte[] logo
        ) {

    /**
     * Línea de detalle de la nota de crédito. Usa código interno en lugar de
     * código principal.
     */
    public record Item(
            String internalCode,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            BigDecimal subtotal,
            List<RideData.Tax> taxes
            ) {

    }

    /**
     * Número de documento formateado: EST-PTO-SEQ (ej: 001-001-000000001).
     */
    public String formattedDocumentNumber() {
        return establishment + "-" + issuePoint + "-" + sequenceNumber;
    }
}
