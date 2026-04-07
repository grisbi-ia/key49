package auracore.key49.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTO de request para crear una nota de débito electrónica. Los nombres de
 * campo se serializan a snake_case automáticamente por la configuración global
 * de Jackson.
 *
 * <p>
 * Diferencia clave con nota de crédito: usa {@code reasons} (motivos con razón
 * + valor) en lugar de {@code items} (ítems con cantidades y precios).
 */
public record CreateDebitNoteRequest(
        String establishment,
        String issuePoint,
        String sequenceNumber,
        LocalDate issueDate,
        RecipientRequest recipient,
        String modifiedDocumentCode,
        String modifiedDocumentNumber,
        LocalDate modifiedDocumentDate,
        List<ReasonRequest> reasons,
        List<TaxRequest> taxes,
        List<PaymentRequest> payments,
        Map<String, String> additionalInfo) {

    public record RecipientRequest(
            String idType,
            String id,
            String name,
            String email,
            String phone) {

    }

    public record ReasonRequest(
            String description,
            BigDecimal amount) {

    }

    public record TaxRequest(
            String code,
            String rateCode,
            BigDecimal rate) {

    }

    public record PaymentRequest(
            String paymentMethod,
            BigDecimal total,
            Integer term,
            String timeUnit) {

    }
}
