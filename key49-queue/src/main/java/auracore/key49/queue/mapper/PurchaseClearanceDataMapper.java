package auracore.key49.queue.mapper;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.xml.builder.PurchaseClearanceData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Mapea Document + Tenant + accessKey a PurchaseClearanceData para generar el
 * XML de liquidación de compra.
 *
 * <p>
 * Extrae ítems, pagos, impuestos totalizados e información adicional del
 * {@code requestPayload} JSON del documento.
 */
@ApplicationScoped
public class PurchaseClearanceDataMapper {

    private final ObjectMapper objectMapper;

    @Inject
    public PurchaseClearanceDataMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Construye un PurchaseClearanceData completo a partir de los datos del
     * documento, el tenant emisor y la clave de acceso generada.
     *
     * @param doc documento con datos del comprobante
     * @param tenant tenant emisor con información tributaria
     * @param accessKey clave de acceso de 49 dígitos
     * @return datos listos para generar el XML de liquidación de compra
     */
    public PurchaseClearanceData build(Document doc, Tenant tenant, String accessKey) {
        var payload = parsePayload(doc.requestPayload);

        return new PurchaseClearanceData(
                buildTaxpayerInfo(tenant),
                accessKey,
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                new PurchaseClearanceData.Supplier(
                        doc.recipientIdType,
                        doc.recipientId,
                        doc.recipientName,
                        doc.recipientAddress),
                payload.items() != null ? payload.items() : List.of(),
                payload.totalTaxes() != null ? payload.totalTaxes() : List.of(),
                payload.payments() != null ? payload.payments() : List.of(),
                doc.subtotalBeforeTax,
                doc.totalDiscount,
                doc.totalAmount,
                doc.currency,
                payload.additionalInfo()
        );
    }

    private PurchaseClearanceData.TaxpayerInfo buildTaxpayerInfo(Tenant tenant) {
        var envCode = "production".equals(tenant.environment) ? "2" : "1";
        return new PurchaseClearanceData.TaxpayerInfo(
                envCode,
                String.valueOf(tenant.emissionType),
                tenant.legalName,
                tenant.tradeName,
                tenant.ruc,
                tenant.mainAddress,
                null,
                tenant.requiredAccounting,
                tenant.specialTaxpayer,
                tenant.withholdingAgent,
                tenant.microEnterpriseRegime ? "CONTRIBUYENTE RÉGIMEN RIMPE" : null
        );
    }

    private RequestPayload parsePayload(String requestPayload) {
        if (requestPayload == null || requestPayload.isBlank()) {
            return new RequestPayload(List.of(), List.of(), List.of(), Map.of());
        }
        try {
            var parsed = objectMapper.readValue(requestPayload, RequestPayload.class);
            return new RequestPayload(
                    parsed.items() != null ? parsed.items() : List.of(),
                    parsed.totalTaxes() != null ? parsed.totalTaxes() : List.of(),
                    parsed.payments() != null ? parsed.payments() : List.of(),
                    parsed.additionalInfo() != null ? parsed.additionalInfo() : Map.of()
            );
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid request payload JSON", e);
        }
    }

    record RequestPayload(
            List<PurchaseClearanceData.Item> items,
            List<PurchaseClearanceData.TotalTax> totalTaxes,
            List<PurchaseClearanceData.Payment> payments,
            Map<String, String> additionalInfo
            ) {

    }
}



  