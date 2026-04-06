package auracore.key49.api.resource;

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

import auracore.key49.api.dto.UpdateProfileRequest;

/**
 * Tests unitarios para UpdateProfileRequest y validaciones del TenantProfileResource.
 */

class TenantProfileResourceTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new JavaTimeModule());

    // ── UpdateProfileRequest ──

    @Nested
    @DisplayName("UpdateProfileRequest")
    class UpdateProfile {

        @Test
        @DisplayName("deserializa JSON con snake_case")
        void deserializesFromJson() throws Exception {
            var json = """
                    {
                      "legal_name": "NUEVA RAZÓN SOCIAL S.A.",
                      "trade_name": "NOMBRE COMERCIAL",
                      "main_address": "Guayaquil, Ecuador",
                      "required_accounting": true,
                      "webhook_url": "https://example.com/webhook",
                      "email_sender_name": "Facturas ACME"
                    }
                    """;

            var request = mapper.readValue(json, UpdateProfileRequest.class);
            assertEquals("NUEVA RAZÓN SOCIAL S.A.", request.legalName());
            assertEquals("NOMBRE COMERCIAL", request.tradeName());
            assertEquals("Guayaquil, Ecuador", request.mainAddress());
            assertTrue(request.requiredAccounting());
            assertEquals("https://example.com/webhook", request.webhookUrl());
            assertEquals("Facturas ACME", request.emailSenderName());
            assertNull(request.replyEmail());
            assertNull(request.specialTaxpayer());
        }

        @Test
        @DisplayName("deserializa JSON parcial — solo webhook")
        void deserializesPartial() throws Exception {
            var json = """
                    {
                      "webhook_url": "https://hooks.example.com/key49",
                      "webhook_secret": "s3cr3t"
                    }
                    """;

            var request = mapper.readValue(json, UpdateProfileRequest.class);
            assertEquals("https://hooks.example.com/key49", request.webhookUrl());
            assertEquals("s3cr3t", request.webhookSecret());
            assertNull(request.legalName());
            assertNull(request.tradeName());
            assertNull(request.mainAddress());
            assertNull(request.requiredAccounting());
        }

        @Test
        @DisplayName("no incluye campos administrativos (status, rateLimitRpm)")
        void excludesAdminFields() {
            // UpdateProfileRequest no tiene campos status ni rateLimitRpm
            var fields = java.util.Arrays.stream(UpdateProfileRequest.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();
            assertFalse(fields.contains("status"), "status no debe estar en UpdateProfileRequest");
            assertFalse(fields.contains("rateLimitRpm"), "rateLimitRpm no debe estar en UpdateProfileRequest");
            assertEquals(12, fields.size(), "UpdateProfileRequest debe tener 12 campos");
        }

        @Test
        @DisplayName("preserva todos los campos del record")
        void preservesAllFields() {
            var req = new UpdateProfileRequest(
                    "Legal", "Trade", "Address",
                    true, "01234", false, "1",
                    "production", "https://hook.com", "secret",
                    "Sender", "reply@test.com");

            assertEquals("Legal", req.legalName());
            assertEquals("Trade", req.tradeName());
            assertEquals("Address", req.mainAddress());
            assertTrue(req.requiredAccounting());
            assertEquals("01234", req.specialTaxpayer());
            assertFalse(req.microEnterpriseRegime());
            assertEquals("1", req.withholdingAgent());
            assertEquals("production", req.environment());
            assertEquals("https://hook.com", req.webhookUrl());
            assertEquals("secret", req.webhookSecret());
            assertEquals("Sender", req.emailSenderName());
            assertEquals("reply@test.com", req.replyEmail());
        }

        @Test
        @DisplayName("serializa a JSON snake_case")
        void serializesToSnakeCase() throws Exception {
            var req = new UpdateProfileRequest(
                    "Legal", null, null, null, null, null, null,
                    null, "https://hook.com", null, "Sender", null);

            var json = mapper.writeValueAsString(req);
            assertTrue(json.contains("\"legal_name\":\"Legal\""));
            assertTrue(json.contains("\"webhook_url\":\"https://hook.com\""));
            assertTrue(json.contains("\"email_sender_name\":\"Sender\""));
        }

        @Test
        @DisplayName("JSON vacío deserializa con todos los campos null")
        void emptyJson() throws Exception {
            var request = mapper.readValue("{}", UpdateProfileRequest.class);
            assertNull(request.legalName());
            assertNull(request.tradeName());
            assertNull(request.mainAddress());
            assertNull(request.requiredAccounting());
            assertNull(request.specialTaxpayer());
            assertNull(request.microEnterpriseRegime());
            assertNull(request.withholdingAgent());
            assertNull(request.environment());
            assertNull(request.webhookUrl());
            assertNull(request.webhookSecret());
            assertNull(request.emailSenderName());
            assertNull(request.replyEmail());
        }
    }

    // ── UpdateProfileRequest → UpdateTenantData mapping ──

    @Nested
    @DisplayName("Mapping a UpdateTenantData")
    class MappingToService {

        @Test
        @DisplayName("convierte a UpdateTenantData sin campos admin")
        void convertsToUpdateData() {
            var req = new UpdateProfileRequest(
                    "Legal", "Trade", "Address",
                    true, "01234", false, "1",
                    "test", "https://hook.com", "secret",
                    "Sender", "reply@test.com");

            var data = new auracore.key49.core.service.TenantAdminService.UpdateTenantData(
                    req.legalName(), req.tradeName(), req.mainAddress(),
                    req.requiredAccounting(), req.specialTaxpayer(),
                    req.microEnterpriseRegime(), req.withholdingAgent(),
                    req.environment(), req.webhookUrl(), req.webhookSecret(),
                    null, req.emailSenderName(),
                    req.replyEmail(), null);

            assertEquals("Legal", data.legalName());
            assertEquals("Trade", data.tradeName());
            assertNull(data.rateLimitRpm(), "rateLimitRpm debe ser null (campo admin)");
            assertNull(data.status(), "status debe ser null (campo admin)");
        }
    }
}
