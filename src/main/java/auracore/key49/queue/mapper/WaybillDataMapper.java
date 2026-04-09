package auracore.key49.queue.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.xml.builder.WaybillData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Mapea Document + Tenant + accessKey a WaybillData para generar el XML de guía
 * de remisión.
 */
@ApplicationScoped
public class WaybillDataMapper {

    private final ObjectMapper objectMapper;

    @Inject
    public WaybillDataMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Construye un WaybillData completo a partir de los datos del documento, el
     * tenant emisor y la clave de acceso generada.
     */
    public WaybillData build(Document doc, Tenant tenant, String accessKey) {
        var payload = parsePayload(doc.requestPayload);

        var carrier = new WaybillData.Carrier(
                payload.carrier() != null ? payload.carrier().idType() : doc.recipientIdType,
                payload.carrier() != null ? payload.carrier().id() : doc.recipientId,
                payload.carrier() != null ? payload.carrier().name() : doc.recipientName,
                null);

        return new WaybillData(
                buildTaxpayerInfo(tenant),
                accessKey,
                doc.establishment,
                doc.issuePoint,
                doc.sequenceNumber,
                doc.issueDate,
                payload.departureAddress(),
                carrier,
                payload.transportStartDate(),
                payload.transportEndDate(),
                payload.licensePlate(),
                mapAddressees(payload.addressees()),
                payload.additionalInfo() != null ? payload.additionalInfo() : Map.of());
    }

    private WaybillData.TaxpayerInfo buildTaxpayerInfo(Tenant tenant) {
        var envCode = "production".equals(tenant.environment) ? "2" : "1";
        return new WaybillData.TaxpayerInfo(
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
                tenant.microEnterpriseRegime ? "CONTRIBUYENTE RÉGIMEN RIMPE" : null);
    }

    private List<WaybillData.Addressee> mapAddressees(List<PayloadAddressee> addressees) {
        if (addressees == null || addressees.isEmpty()) {
            return List.of();
        }
        return addressees.stream().map(this::mapAddressee).toList();
    }

    private WaybillData.Addressee mapAddressee(PayloadAddressee addr) {
        return new WaybillData.Addressee(
                addr.id(),
                addr.name(),
                addr.address(),
                addr.transferReason(),
                addr.customsDocument(),
                addr.destinationEstablishment(),
                addr.route(),
                addr.supportDocumentCode(),
                addr.supportDocumentNumber(),
                addr.supportDocumentAuthNumber(),
                addr.supportDocumentIssueDate(),
                mapItems(addr.items()));
    }

    private List<WaybillData.Item> mapItems(List<PayloadItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
                .map(i -> new WaybillData.Item(
                i.mainCode(), i.auxiliaryCode(), i.description(),
                i.quantity(), mapItemDetails(i.additionalDetails())))
                .toList();
    }

    private List<WaybillData.ItemDetail> mapItemDetails(List<PayloadItemDetail> details) {
        if (details == null || details.isEmpty()) {
            return List.of();
        }
        return details.stream()
                .map(d -> new WaybillData.ItemDetail(d.name(), d.value()))
                .toList();
    }

    private WaybillPayload parsePayload(String requestPayload) {
        if (requestPayload == null || requestPayload.isBlank()) {
            return new WaybillPayload(null, null, null, null, null, List.of(), Map.of());
        }
        try {
            var parsed = objectMapper.readValue(requestPayload, WaybillPayload.class);
            return new WaybillPayload(
                    parsed.departureAddress(),
                    parsed.carrier(),
                    parsed.transportStartDate(),
                    parsed.transportEndDate(),
                    parsed.licensePlate(),
                    parsed.addressees() != null ? parsed.addressees() : List.of(),
                    parsed.additionalInfo() != null ? parsed.additionalInfo() : Map.of());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid request payload JSON", e);
        }
    }

    // ── Payload records ──
    record WaybillPayload(
            String departureAddress,
            PayloadCarrier carrier,
            LocalDate transportStartDate,
            LocalDate transportEndDate,
            String licensePlate,
            List<PayloadAddressee> addressees,
            Map<String, String> additionalInfo) {

    }

    record PayloadCarrier(
            String idType, String id, String name, String email, String phone) {

    }

    record PayloadAddressee(
            String id, String name, String address, String transferReason,
            String customsDocument, String destinationEstablishment,
            String route,
            String supportDocumentCode, String supportDocumentNumber,
            String supportDocumentAuthNumber, LocalDate supportDocumentIssueDate,
            List<PayloadItem> items) {

    }

    record PayloadItem(
            String mainCode, String auxiliaryCode, String description,
            BigDecimal quantity, List<PayloadItemDetail> additionalDetails) {

    }

    record PayloadItemDetail(String name, String value) {

    }
}
