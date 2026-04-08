package auracore.key49.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTO de request para crear una guía de remisión electrónica. Los nombres de
 * campo se serializan a snake_case automáticamente por la configuración global
 * de Jackson.
 *
 * <p>
 * Diferencia clave con otros comprobantes: la guía de remisión no tiene
 * impuestos, pagos ni totales. Es un documento de transporte con datos del
 * transportista, destinatarios e ítems transportados.
 */
public record CreateWaybillRequest(
        String establishment,
        String issuePoint,
        String sequenceNumber,
        LocalDate issueDate,
        String departureAddress,
        CarrierRequest carrier,
        LocalDate transportStartDate,
        LocalDate transportEndDate,
        String licensePlate,
        List<AddresseeRequest> addressees,
        Map<String, String> additionalInfo) {

    public record CarrierRequest(
            String idType,
            String id,
            String name,
            String email,
            String phone) {

    }

    public record AddresseeRequest(
            String id,
            String name,
            String address,
            String transferReason,
            String customsDocument,
            String destinationEstablishment,
            String route,
            String supportDocumentCode,
            String supportDocumentNumber,
            String supportDocumentAuthNumber,
            LocalDate supportDocumentIssueDate,
            List<ItemRequest> items) {

    }

    public record ItemRequest(
            String mainCode,
            String auxiliaryCode,
            String description,
            BigDecimal quantity,
            List<ItemDetailRequest> additionalDetails) {

    }

    public record ItemDetailRequest(
            String name,
            String value) {

    }
}

        
        