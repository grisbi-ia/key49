package auracore.key49.ride.generator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Datos necesarios para generar el RIDE (PDF) de una nota de débito. Incluye
 * datos del documento modificado y motivos de la nota de débito.
 */
public record DebitNoteRideData(
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
        List<Reason> reasons,
        List<RideData.TotalTax> totalTaxes,
        BigDecimal totalWithoutTax,
        BigDecimal totalValue,
        List<RideData.Payment> payments,
        Map<String, String> additionalInfo,
        boolean authorized,
        byte[] logo
        ) {

    /**
     * Motivo de la nota de débito con su valor.
     */
    public record Reason(
            String description,
            BigDecimal amount
            ) {

    }

    /**
     * Número de documento formateado: EST-PTO-SEQ (ej: 001-001-000000001).
     */
    public String formattedDocumentNumber() {
        return establishment + "-" + issuePoint + "-" + sequenceNumber;
    }
}
