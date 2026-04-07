package auracore.key49.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTO de request para crear una nota de crédito electrónica. Los nombres de
 * campo se serializan a snake_case automáticamente por la configuración global
 * de Jackson.
 */
public record CreateCreditNoteRequest(
        String establishment,
        String issuePoint,
        String sequenceNumber,
        LocalDate issueDate,
        RecipientRequest recipient,
        String modifiedDocumentCode,
        String modifiedDocumentNumber,
        LocalDate modifiedDocumentDate,
        String reason,
        List<ItemRequest> items,
        Map<String, String> additionalInfo) {

    public record RecipientRequest(
            String idType,
            String id,
            String name,
            String email,
            String phone) {

    }

    public record ItemRequest(
            String internalCode,
            String additionalCode,
            String description,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            List<TaxRequest> taxes) {

    }

    public record TaxRequest(
            String code,
            String rateCode,
            BigDecimal rate) {

    }
}
