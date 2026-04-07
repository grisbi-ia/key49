package auracore.key49.xml.builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Datos necesarios para generar el XML de una guía de remisión electrónica
 * conforme al XSD GuiaRemision v1.1.0 del SRI.
 */
public record WaybillData(
        TaxpayerInfo taxpayer,
        String accessKey,
        String establishment,
        String issuePoint,
        String sequenceNumber,
        LocalDate issueDate,
        String departureAddress,
        Carrier carrier,
        LocalDate transportStartDate,
        LocalDate transportEndDate,
        String licensePlate,
        List<Addressee> addressees,
        Map<String, String> additionalInfo) {

    public record TaxpayerInfo(
            String environment, String emissionType, String legalName,
            String tradeName, String ruc, String mainAddress,
            String establishmentAddress, boolean requiredAccounting,
            String specialTaxpayer, String withholdingAgent,
            String rimpeContributor) {

    }

    public record Carrier(
            String idType, String id, String name, String rise) {

    }

    public record Addressee(
            String id, String name, String address, String transferReason,
            String customsDocument, String destinationEstablishment,
            String route,
            String supportDocumentCode, String supportDocumentNumber,
            String supportDocumentAuthNumber, LocalDate supportDocumentIssueDate,
            List<Item> items) {

    }

    public record Item(
            String mainCode, String auxiliaryCode, String description,
            BigDecimal quantity, List<ItemDetail> additionalDetails) {

    }

    public record ItemDetail(String name, String value) {

    }
}
