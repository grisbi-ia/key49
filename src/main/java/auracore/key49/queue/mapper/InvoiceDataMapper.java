package auracore.key49.queue.mapper;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.xml.builder.InvoiceData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Mapea Document + Tenant + accessKey a InvoiceData para generar el XML de factura.
 *
 * <p>Extrae ítems, pagos, impuestos totalizados e información adicional
 * del {@code requestPayload} JSON del documento.
 */
@ApplicationScoped
public class InvoiceDataMapper {

    private final ObjectMapper objectMapper;

    @Inject
    public InvoiceDataMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Construye un InvoiceData completo a partir de los datos del documento,
     * el tenant emisor y la clave de acceso generada.
     *
     * @param doc       documento con datos del comprobante
     * @param tenant    tenant emisor con información tributaria
     * @param accessKey clave de acceso de 49 dígitos
     * @return datos listos para generar el XML de factura
     */
    public InvoiceData build(Document doc, Tenant tenant, String accessKey) {
        var payload = parsePayload(doc.requestPayload);

        return new InvoiceData(
                buildTaxpayerInfo(tenant),
                accessKey,
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                new InvoiceData.Recipient(
                        doc.recipientIdType,
                        doc.recipientId,
                        doc.recipientName,
                        doc.recipientAddress),
                payload.items() != null ? payload.items() : List.of(),
                payload.totalTaxes() != null ? payload.totalTaxes() : List.of(),
                payload.payments() != null ? payload.payments() : List.of(),
                doc.subtotalBeforeTax,
                doc.totalDiscount,
                doc.tip,
                doc.totalAmount,
                doc.currency,
                payload.additionalInfo()
        );
    }

    private InvoiceData.TaxpayerInfo buildTaxpayerInfo(Tenant tenant) {
        var envCode = "production".equals(tenant.environment) ? "2" : "1";
        return new InvoiceData.TaxpayerInfo(
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
            List<InvoiceData.Item> items,
            List<InvoiceData.TotalTax> totalTaxes,
            List<InvoiceData.Payment> payments,
            Map<String, String> additionalInfo
    ) {}
}
