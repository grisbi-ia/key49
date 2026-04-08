package auracore.key49.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTO de request para crear una liquidación de compra electrónica. Los nombres
 * de campo se serializan a snake_case automáticamente por la configuración
 * global de Jackson.
 *
 * <p>
 * Estructura similar a factura pero el sujeto es un proveedor (no un
 * comprador).
 */
public record CreatePurchaseClearanceRequest(
        String establishment,
        String issuePoint,
        String sequenceNumber,
        LocalDate issueDate,
        SupplierRequest supplier,
        List<ItemRequest> items,
        List<PaymentRequest> payments,
        Map<String, String> additionalInfo) {

    public record SupplierRequest(
            String idType,
            String id,
            String name,
            String address,
            String email,
            String phone) {

    }

    public record ItemRequest(
            String mainCode,
            String auxiliaryCode,
            String description,
            String unitOfMeasure,
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

    public record PaymentRequest(
            String paymentMethod,
            BigDecimal total,
            int term,
            String timeUnit) {

    }
}
