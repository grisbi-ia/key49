package auracore.key49.xml.builder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fixtures de datos para tests de guías de remisión.
 */
final class WaybillDataFixtures {

    private WaybillDataFixtures() {
    }

    static WaybillData.TaxpayerInfo defaultTaxpayer() {
        return new WaybillData.TaxpayerInfo(
                "1", "1", "EMPRESA DE PRUEBAS S.A.",
                "PRUEBAS COMERCIAL", "1792146739001",
                "Quito, Av. Amazonas N24-345",
                "Quito, Sucursal Norte", true,
                "12345", "1", "CONTRIBUYENTE RÉGIMEN RIMPE");
    }

    static WaybillData.Carrier defaultCarrier() {
        return new WaybillData.Carrier("04", "1790016919001",
                "TRANSPORTES DEL NORTE CIA. LTDA.", null);
    }

    static WaybillData.Carrier minimalCarrier() {
        return new WaybillData.Carrier("05", "1710034065",
                "Juan Pérez", null);
    }

    static WaybillData.ItemDetail sampleDetail() {
        return new WaybillData.ItemDetail("Lote", "L-2025-001");
    }

    static WaybillData.Item defaultItem() {
        return new WaybillData.Item(
                "PROD001", "AUX001", "Producto de prueba A",
                new BigDecimal("100.000000"),
                List.of(sampleDetail()));
    }

    static WaybillData.Item simpleItem() {
        return new WaybillData.Item(
                null, null, "Producto sin código",
                new BigDecimal("5.500000"),
                List.of());
    }

    static WaybillData.Item itemWithMultipleDetails() {
        return new WaybillData.Item(
                "PROD002", null, "Producto de prueba B",
                new BigDecimal("250.000000"),
                List.of(
                        new WaybillData.ItemDetail("Lote", "L-2025-002"),
                        new WaybillData.ItemDetail("Fecha Vencimiento", "2026-12-31"),
                        new WaybillData.ItemDetail("Ubicación", "Bodega 3")));
    }

    static WaybillData.Addressee defaultAddressee() {
        return new WaybillData.Addressee(
                "1790016919001", "CLIENTE NACIONAL CIA. LTDA.",
                "Guayaquil, Av. 9 de Octubre 100",
                "Venta de mercadería",
                null, "002", "Quito-Guayaquil",
                "01", "001-001-000000234",
                "1503202501179214673900110010010000002340000002341",
                LocalDate.of(2025, 3, 15),
                List.of(defaultItem()));
    }

    static WaybillData.Addressee minimalAddressee() {
        return new WaybillData.Addressee(
                "1710034065", "Juan Pérez",
                "Cuenca, Calle Larga 200",
                "Traslado entre bodegas",
                null, null, null,
                null, null, null, null,
                List.of(simpleItem()));
    }

    static WaybillData.Addressee addresseeWithMultipleItems() {
        return new WaybillData.Addressee(
                "1790016919001", "DISTRIBUIDORA CENTRAL S.A.",
                "Ambato, Av. Cevallos 456",
                "Distribución",
                "DAU-2025-001", "003", "Quito-Ambato",
                "01", "001-001-000000500",
                "4904202501179214673900110010010000005000000005001",
                LocalDate.of(2025, 4, 1),
                List.of(defaultItem(), simpleItem(), itemWithMultipleDetails()));
    }

    static WaybillData simpleWaybill() {
        return new WaybillData(
                defaultTaxpayer(),
                "1504202506179214673900110010010000001230000001231",
                "001", "001", "000000123",
                LocalDate.of(2025, 4, 15),
                "Quito, Bodega Central Km 10",
                defaultCarrier(),
                LocalDate.of(2025, 4, 15),
                LocalDate.of(2025, 4, 16),
                "PBB-1234",
                List.of(defaultAddressee()),
                Map.of("Email", "transportes@test.com"));
    }

    static WaybillData multiAddresseeWaybill() {
        return new WaybillData(
                defaultTaxpayer(),
                "1504202506179214673900110010010000004560000004561",
                "001", "001", "000000456",
                LocalDate.of(2025, 4, 15),
                "Quito, Parque Industrial",
                defaultCarrier(),
                LocalDate.of(2025, 4, 15),
                LocalDate.of(2025, 4, 18),
                "ABC-7890",
                List.of(defaultAddressee(), minimalAddressee(), addresseeWithMultipleItems()),
                Map.of("Email", "transportes@test.com", "Teléfono", "0991234567"));
    }

    static WaybillData minimalWaybill() {
        var taxpayer = new WaybillData.TaxpayerInfo(
                "1", "1", "EMPRESA MÍNIMA S.A.",
                null, "1792146739001",
                "Quito", null, false,
                null, null, null);

        return new WaybillData(
                taxpayer,
                "1504202506179214673900110010010000007890000007891",
                "001", "001", "000000789",
                LocalDate.of(2025, 4, 15),
                "Guayaquil, Puerto Principal",
                minimalCarrier(),
                LocalDate.of(2025, 4, 15),
                LocalDate.of(2025, 4, 15),
                "GYE-001",
                List.of(minimalAddressee()),
                Map.of());
    }
}
