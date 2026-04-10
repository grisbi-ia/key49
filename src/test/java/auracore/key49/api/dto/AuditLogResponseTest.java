package auracore.key49.api.dto;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import auracore.key49.core.model.AuditLog;

/**
 * Tests unitarios para AuditLogResponse.
 */
class AuditLogResponseTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new JavaTimeModule());

    private AuditLog createTestEntry() {
        var entry = new AuditLog();
        entry.id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        entry.tenantId = UUID.fromString("00000000-0000-0000-0000-000000000099");
        entry.actor = "fec_test";
        entry.action = "document.voided";
        entry.resource = "document";
        entry.resourceId = UUID.fromString("00000000-0000-0000-0000-000000000055");
        entry.ipAddress = "203.0.113.50";
        entry.details = "{\"reason\":\"Error en factura\"}";
        entry.createdAt = Instant.parse("2026-04-10T15:30:00Z");
        return entry;
    }

    @Nested
    @DisplayName("fromEntity")
    class FromEntity {

        @Test
        @DisplayName("mapea todos los campos de la entidad")
        void mapsAllFields() {
            var entry = createTestEntry();
            var response = AuditLogResponse.fromEntity(entry);

            assertEquals(entry.id, response.id());
            assertEquals(entry.tenantId, response.tenantId());
            assertEquals("fec_test", response.actor());
            assertEquals("document.voided", response.action());
            assertEquals("document", response.resource());
            assertEquals(entry.resourceId, response.resourceId());
            assertEquals("203.0.113.50", response.ipAddress());
            assertNotNull(response.details());
            assertEquals(Instant.parse("2026-04-10T15:30:00Z"), response.createdAt());
        }

        @Test
        @DisplayName("mapea campos opcionales null")
        void mapsNullOptionalFields() {
            var entry = new AuditLog();
            entry.id = UUID.randomUUID();
            entry.tenantId = UUID.randomUUID();
            entry.actor = "admin";
            entry.action = "tenant.created";
            entry.resource = "tenant";
            entry.resourceId = null;
            entry.ipAddress = null;
            entry.details = null;
            entry.createdAt = Instant.now();

            var response = AuditLogResponse.fromEntity(entry);
            assertNull(response.resourceId());
            assertNull(response.ipAddress());
            assertNull(response.details());
        }
    }

    @Nested
    @DisplayName("Serialización JSON")
    class Serialization {

        @Test
        @DisplayName("serializa con snake_case")
        void serializesWithSnakeCase() throws Exception {
            var entry = createTestEntry();
            var response = AuditLogResponse.fromEntity(entry);
            var json = mapper.writeValueAsString(response);

            assertTrue(json.contains("\"tenant_id\""));
            assertTrue(json.contains("\"ip_address\""));
            assertTrue(json.contains("\"resource_id\""));
            assertTrue(json.contains("\"created_at\""));
            assertTrue(json.contains("\"document.voided\""));
        }

        @Test
        @DisplayName("details se serializa como string JSON")
        void serializesDetailsAsString() throws Exception {
            var entry = createTestEntry();
            var response = AuditLogResponse.fromEntity(entry);
            var json = mapper.writeValueAsString(response);

            assertTrue(json.contains("\"details\""));
            assertTrue(json.contains("Error en factura"));
        }
    }
}


                                                                                                                                                                                                                                                                                                                                                                                          

                                                                                                                                                                                                                                                                                                                                                                                          

                                                                                                                                                                                                                                                                                                                                                                                          

                                                                                                                                                                                                                                                                                                                                                                                          