package auracore.key49.ride.generator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Datos necesarios para generar el RIDE (PDF) de una guía de remisión. Incluye
 * datos del transportista, ruta, destinatarios con sus ítems.
 */

public record WaybillRideData(
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
        String departureAddress,
        String carrierName,
        String carrierIdType,
        String carrierId,
        LocalDate transportStartDate,
        LocalDate transportEndDate,
        String licensePlate,
        List<AddresseeSummary> addressees,
        Map<String, String> additionalInfo,
        boolean authorized,
        byte[] logo) {

    /**
     * Resumen de un destinatario con sus ítems.
     */
    public record AddresseeSummary(
            String id, String name, String address,
            String transferReason,
            String supportDocumentNumber,
            List<ItemSummary> items) {

    }

    /**
     * Resumen de un ítem en el detalle de un destinatario.
     */
    public record ItemSummary(
            String mainCode, String description, BigDecimal quantity) {

    }

    /**
     * Número de documento formateado: EST-PTO-SEQ (ej: 001-001-000000001).
     */
    public String formattedDocumentNumber() {
        return establishment + "-" + issuePoint + "-" + sequenceNumber;
    }
}
