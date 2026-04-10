package auracore.key49.api.dto;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import auracore.key49.core.model.Tenant;

/**
 * Tests unitarios para los DTOs de gestión de tenants.
 */
class TenantDtoTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new JavaTimeModule());

    // ── Helpers ──
    private Tenant createTestTenant() {
        var t = new Tenant();
        t.id = UUID.randomUUID();
        t.ruc = "1790016919001";
        t.legalName = "EMPRESA DE PRUEBA S.A.";
        t.tradeName = "EMPRESA PRUEBA";
        t.mainAddress = "Quito, Ecuador";
        t.requiredAccounting = true;
        t.specialTaxpayer = null;
        t.microEnterpriseRegime = false;
        t.withholdingAgent = null;
        t.environment = "test";
        t.schemaName = "tenant_empresa_prueba";
        t.status = "active";
        t.rateLimitRpm = 100;
        t.createdAt = Instant.parse("2026-01-01T00:00:00Z");
        t.updatedAt = Instant.parse("2026-04-01T00:00:00Z");
        return t;
    }

    // ── TenantResponse.fromEntity ──
    @Nested
    @DisplayName("TenantResponse.fromEntity")
    class FromEntity {

        @Test
        @DisplayName("mapea campos básicos")
        void mapsBasicFields() {
            var tenant = createTestTenant();
            var response = TenantResponse.fromEntity(tenant);

            assertEquals(tenant.id, response.id());
            assertEquals("1790016919001", response.ruc());
            assertEquals("EMPRESA DE PRUEBA S.A.", response.legalName());
            assertEquals("EMPRESA PRUEBA", response.tradeName());
            assertEquals("Quito, Ecuador", response.mainAddress());
            assertTrue(response.requiredAccounting());
            assertFalse(response.microEnterpriseRegime());
            assertEquals("test", response.environment());
            assertEquals("tenant_empresa_prueba", response.schemaName());
            assertEquals("active", response.status());
        }

        @Test
        @DisplayName("sin certificado — configured=false, valid=false")
        void noCertificate() {
            var tenant = createTestTenant();
            tenant.certificateP12 = null;

            var response = TenantResponse.fromEntity(tenant);
            assertNotNull(response.certificate());
            assertFalse(response.certificate().configured());
            assertFalse(response.certificate().valid());
            assertNull(response.certificate().subject());
        }

        @Test
        @DisplayName("con certificado válido — configured=true, valid=true")
        void validCertificate() {
            var tenant = createTestTenant();
            tenant.certificateP12 = new byte[]{1, 2, 3};
            tenant.certificateSubject = "CN=Test, O=Company";
            tenant.certificateSerial = "ABC123";
            tenant.certificateExpiration = Instant.now().plusSeconds(86400 * 365);

            var response = TenantResponse.fromEntity(tenant);
            assertTrue(response.certificate().configured());
            assertTrue(response.certificate().valid());
            assertEquals("CN=Test, O=Company", response.certificate().subject());
            assertEquals("ABC123", response.certificate().serial());
        }

        @Test
        @DisplayName("con certificado expirado — configured=true, valid=false")
        void expiredCertificate() {
            var tenant = createTestTenant();
            tenant.certificateP12 = new byte[]{1, 2, 3};
            tenant.certificateSubject = "CN=Expired";
            tenant.certificateSerial = "DEF456";
            tenant.certificateExpiration = Instant.now().minusSeconds(86400);

            var response = TenantResponse.fromEntity(tenant);
            assertTrue(response.certificate().configured());
            assertFalse(response.certificate().valid());
        }

        @Test
        @DisplayName("sin rotación pendiente — pendingRotation=false")
        void noPendingRotation() {
            var tenant = createTestTenant();
            tenant.certificateP12 = new byte[]{1, 2, 3};
            tenant.certificateSubject = "CN=Test";
            tenant.certificateSerial = "ABC";
            tenant.certificateExpiration = Instant.now().plusSeconds(86400);
            tenant.pendingCertificateP12 = null;

            var response = TenantResponse.fromEntity(tenant);
            assertFalse(response.certificate().pendingRotation());
        }

        @Test
        @DisplayName("con rotación pendiente — pendingRotation=true")
        void withPendingRotation() {
            var tenant = createTestTenant();
            tenant.certificateP12 = new byte[]{1, 2, 3};
            tenant.certificateSubject = "CN=Active";
            tenant.certificateSerial = "ACT123";
            tenant.certificateExpiration = Instant.now().plusSeconds(86400);
            tenant.pendingCertificateP12 = new byte[]{4, 5, 6};
            tenant.pendingCertificateSubject = "CN=Pending";
            tenant.pendingCertificateSerial = "PEN456";
            tenant.pendingCertificateExpiration = Instant.now().plusSeconds(86400 * 365);

            var response = TenantResponse.fromEntity(tenant);
            assertTrue(response.certificate().pendingRotation());
            assertEquals("CN=Active", response.certificate().subject());
        }

        @Test
        @DisplayName("sin certificado con rotación pendiente — pendingRotation=true, configured=false")
        void noCertButPendingRotation() {
            var tenant = createTestTenant();
            tenant.certificateP12 = null;
            tenant.pendingCertificateP12 = new byte[]{4, 5, 6};

            var response = TenantResponse.fromEntity(tenant);
            assertFalse(response.certificate().configured());
            assertTrue(response.certificate().pendingRotation());
        }

        @Test
        @DisplayName("incluye timestamps")
        void includesTimestamps() {
            var tenant = createTestTenant();
            var response = TenantResponse.fromEntity(tenant);

            assertEquals(Instant.parse("2026-01-01T00:00:00Z"), response.createdAt());
            assertEquals(Instant.parse("2026-04-01T00:00:00Z"), response.updatedAt());
        }

        @Test
        @DisplayName("incluye configuración de webhook y rate limit")
        void includesConfig() {
            var tenant = createTestTenant();
            tenant.webhookUrl = "https://example.com/webhook";
            tenant.rateLimitRpm = 200;
            tenant.rateLimitWriteRpm = 50;
            tenant.rateLimitReadRpm = 500;
            tenant.emailSenderName = "Facturas ACME";
            tenant.replyEmail = "contabilidad@acme.com";

            var response = TenantResponse.fromEntity(tenant);
            assertEquals("https://example.com/webhook", response.webhookUrl());
            assertEquals(200, response.rateLimitRpm());
            assertEquals(50, response.rateLimitWriteRpm());
            assertEquals(500, response.rateLimitReadRpm());
            assertEquals("Facturas ACME", response.emailSenderName());
            assertEquals("contabilidad@acme.com", response.replyEmail());
        }
    }

    // ── CreateTenantRequest serialization ──
    @Nested
    @DisplayName("CreateTenantRequest")
    class CreateRequest {

        @Test
        @DisplayName("deserializa JSON con snake_case")
        void deserializesFromJson() throws Exception {
            var json = """
                    {
                      "ruc": "1790016919001",
                      "legal_name": "EMPRESA S.A.",
                      "trade_name": "EMPRESA",
                      "main_address": "Quito",
                      "required_accounting": true,
                      "micro_enterprise_regime": false,
                      "environment": "test",
                      "schema_name": "tenant_test_123"
                    }
                    """;

            var request = mapper.readValue(json, CreateTenantRequest.class);
            assertEquals("1790016919001", request.ruc());
            assertEquals("EMPRESA S.A.", request.legalName());
            assertEquals("tenant_test_123", request.schemaName());
            assertTrue(request.requiredAccounting());
        }

        @Test
        @DisplayName("preserva todos los campos")
        void preservesAllFields() {
            var req = new CreateTenantRequest(
                    "1790016919001", "Legal", "Trade", "Addr",
                    true, "01234", true, "1", "production", "schema_x");

            assertEquals("1790016919001", req.ruc());
            assertEquals("production", req.environment());
            assertEquals("schema_x", req.schemaName());
        }
    }

    // ── UpdateTenantRequest ──
    @Nested
    @DisplayName("UpdateTenantRequest")
    class UpdateRequest {

        @Test
        @DisplayName("deserializa JSON parcial")
        void deserializesPartialJson() throws Exception {
            var json = """
                    {
                      "legal_name": "NUEVO NOMBRE S.A.",
                      "status": "suspended"
                    }
                    """;

            var request = mapper.readValue(json, UpdateTenantRequest.class);
            assertEquals("NUEVO NOMBRE S.A.", request.legalName());
            assertEquals("suspended", request.status());
            assertNull(request.tradeName());
            assertNull(request.environment());
        }
    }

    // ── CertificateStatusResponse ──
    @Nested
    @DisplayName("CertificateStatusResponse")
    class CertStatusResponse {

        @Test
        @DisplayName("serializa correctamente")
        void serializes() throws Exception {
            var exp = Instant.parse("2027-06-15T00:00:00Z");
            var response = new CertificateStatusResponse(
                    "CN=Test", "ABC123", exp, "CN=CA", true, 365);

            var json = mapper.writeValueAsString(response);
            assertTrue(json.contains("\"subject\":\"CN=Test\""));
            assertTrue(json.contains("\"serial\":\"ABC123\""));
            assertTrue(json.contains("\"valid\":true"));
        }

        @Test
        @DisplayName("omite campos null")
        void omitsNull() throws Exception {
            var response = new CertificateStatusResponse(
                    "CN=Test", "ABC123", Instant.now(), null, true, 100);

            var json = mapper.writeValueAsString(response);
            assertFalse(json.contains("\"issuer\""));
        }

        @Test
        @DisplayName("serializa con certificado pendiente")
        void serializesWithPendingCert() throws Exception {
            var exp = Instant.parse("2027-06-15T00:00:00Z");
            var pendingExp = Instant.parse("2028-06-15T00:00:00Z");
            var pending = new CertificateStatusResponse.PendingCertificate(
                    "CN=Pending", "PEN789", pendingExp, true, 730);
            var response = new CertificateStatusResponse(
                    "CN=Active", "ACT123", exp, "CN=CA", true, 365, pending);

            var json = mapper.writeValueAsString(response);
            assertTrue(json.contains("\"subject\":\"CN=Active\""));
            assertTrue(json.contains("\"pending_certificate\""));
            assertTrue(json.contains("\"CN=Pending\""));
            assertTrue(json.contains("\"PEN789\""));
        }

        @Test
        @DisplayName("omite pending_certificate cuando es null")
        void omitsPendingCertWhenNull() throws Exception {
            var response = new CertificateStatusResponse(
                    "CN=Test", "ABC123", Instant.now(), null, true, 100);

            var json = mapper.writeValueAsString(response);
            assertFalse(json.contains("pending_certificate"));
        }

        @Test
        @DisplayName("constructor de 6 args establece pending null")
        void sixArgConstructorSetsPendingNull() {
            var response = new CertificateStatusResponse(
                    "CN=Test", "ABC123", Instant.now(), null, true, 100);
            assertNull(response.pendingCertificate());
        }

        @Test
        @DisplayName("PendingCertificate preserva campos")
        void pendingCertificatePreservesFields() {
            var exp = Instant.parse("2028-01-01T00:00:00Z");
            var pending = new CertificateStatusResponse.PendingCertificate(
                    "CN=New Cert", "SER001", exp, true, 500);

            assertEquals("CN=New Cert", pending.subject());
            assertEquals("SER001", pending.serial());
            assertEquals(exp, pending.expiresAt());
            assertTrue(pending.valid());
            assertEquals(500, pending.daysUntilExpiration());
        }

        @Test
        @DisplayName("CertificateSummary backward-compat constructor sin pendingRotation")
        void certSummaryBackwardCompat() {
            var summary = new TenantResponse.CertificateSummary(
                    "CN=Test", "SER", Instant.now(), true, true);
            assertFalse(summary.pendingRotation());
        }
    }
}
