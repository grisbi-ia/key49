package auracore.key49.xml.builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixtures de datos para tests de comprobantes de retención.
 */

final class WithholdingDataFixtures {

    private WithholdingDataFixtures() {
    }

    static WithholdingData.TaxpayerInfo defaultTaxpayer() {
        return new WithholdingData.TaxpayerInfo(
                "1", "1", "EMPRESA DE PRUEBAS S.A.",
                "PRUEBAS COMERCIAL", "1792146739001",
                "Quito, Av. Amazonas N24-345",
                "Quito, Sucursal Norte", true,
                "12345", "1", "CONTRIBUYENTE RÉGIMEN RIMPE");
    }

    static WithholdingData.Subject defaultSubject() {
        return new WithholdingData.Subject("04", "1790016919001",
                "PROVEEDOR NACIONAL CIA. LTDA.", "01");
    }

    static WithholdingData.Subject minimalSubject() {
        return new WithholdingData.Subject("05", "1710034065",
                "Juan Pérez", null);
    }

    static WithholdingData.SupportingDocTax taxIva15() {
        return new WithholdingData.SupportingDocTax(
                "2", "4", new BigDecimal("1000.00"),
                new BigDecimal("15.00"), new BigDecimal("150.00"));
    }

    static WithholdingData.SupportingDocTax taxIva0() {
        return new WithholdingData.SupportingDocTax(
                "2", "0", new BigDecimal("500.00"),
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    static WithholdingData.WithholdingLine retentionRenta() {
        return new WithholdingData.WithholdingLine(
                "1", "303", new BigDecimal("1000.00"),
                new BigDecimal("10.00"), new BigDecimal("100.00"));
    }

    static WithholdingData.WithholdingLine retentionIva() {
        return new WithholdingData.WithholdingLine(
                "2", "725", new BigDecimal("150.00"),
                new BigDecimal("30.00"), new BigDecimal("45.00"));
    }

    static WithholdingData.WithholdingLine retentionIsd() {
        return new WithholdingData.WithholdingLine(
                "6", "4580", new BigDecimal("2000.00"),
                new BigDecimal("5.00"), new BigDecimal("100.00"));
    }

    static WithholdingData.Payment defaultPayment() {
        return new WithholdingData.Payment("20", new BigDecimal("1150.00"));
    }

    static WithholdingData.SupportingDocument defaultSupportingDocument() {
        return new WithholdingData.SupportingDocument(
                "01", "01", "001001000000234",
                LocalDate.of(2025, 3, 15), null,
                "1503202501179214673900110010010000002340000002341",
                "01", null, null, null, null, null,
                new BigDecimal("1000.00"), new BigDecimal("1150.00"),
                List.of(taxIva15()),
                List.of(retentionRenta(), retentionIva()),
                List.of(defaultPayment()));
    }

    static WithholdingData.SupportingDocument minimalSupportingDocument() {
        return new WithholdingData.SupportingDocument(
                "01", "01", "001001000000100",
                LocalDate.of(2025, 3, 10), null,
                null, "01", null, null, null, null, null,
                new BigDecimal("500.00"), new BigDecimal("500.00"),
                List.of(taxIva0()),
                List.of(retentionRenta()),
                List.of(new WithholdingData.Payment("20", new BigDecimal("500.00"))));
    }

    static WithholdingData simpleWithholding() {
        return new WithholdingData(
                defaultTaxpayer(),
                "1504202507179214673900110010010000001230000001231",
                "001", "001", "000000123",
                LocalDate.of(2025, 4, 15),
                defaultSubject(),
                "03/2025", false,
                List.of(defaultSupportingDocument()),
                Map.of("Email", "proveedor@test.com"));
    }

    static WithholdingData multiDocWithholding() {
        return new WithholdingData(
                defaultTaxpayer(),
                "1504202507179214673900110010010000004560000004561",
                "001", "001", "000000456",
                LocalDate.of(2025, 4, 15),
                defaultSubject(),
                "03/2025", true,
                List.of(defaultSupportingDocument(), minimalSupportingDocument()),
                Map.of("Email", "proveedor@test.com", "Teléfono", "0991234567"));
    }

    static WithholdingData minimalWithholding() {
        var taxpayer = new WithholdingData.TaxpayerInfo(
                "1", "1", "EMPRESA MÍNIMA S.A.",
                null, "1792146739001",
                "Quito", null, false,
                null, null, null);

        return new WithholdingData(
                taxpayer,
                "1504202507179214673900110010010000007890000007891",
                "001", "001", "000000789",
                LocalDate.of(2025, 4, 15),
                minimalSubject(),
                "04/2025", false,
                List.of(minimalSupportingDocument()),
                Map.of());
    }
}
