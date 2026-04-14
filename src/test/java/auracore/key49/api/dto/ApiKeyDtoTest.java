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

import auracore.key49.core.model.ApiKey;

/**
 * Tests unitarios para los DTOs de gestión de API keys.
 */
class ApiKeyDtoTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .registerModule(new JavaTimeModule());

    // ── Helpers ──
    private ApiKey createTestApiKey() {
        var key = new ApiKey();
        key.id = UUID.randomUUID();
        key.tenantId = UUID.randomUUID();
        key.keyPrefix = "k49";
        key.keyHash = "abc123def456";
        key.name = "ERP Production Key";
        key.permissions = "*";
        key.status = "active";
        key.createdAt = Instant.parse("2026-04-01T00:00:00Z");
        return key;
    }

    // ── CreateApiKeyRequest ──
    @Nested
    @DisplayName("CreateApiKeyRequest")
    class CreateRequest {

        @Test
        @DisplayName("deserializa JSON con snake_case")
        void deserializesFromJson() throws Exception {
            var json = """
                    {
                      "name": "ERP Key",
                      "permissions": "invoices:*",
                      "expires_at": "2027-04-01T00:00:00Z"
                    }
                    """;

            var request = mapper.readValue(json, CreateApiKeyRequest.class);
            assertEquals("ERP Key", request.name());
            assertEquals("invoices:*", request.permissions());
            assertEquals(Instant.parse("2027-04-01T00:00:00Z"), request.expiresAt());
        }

        @Test
        @DisplayName("deserializa con campos opcionales null")
        void deserializesMinimal() throws Exception {
            var json = """
                    {
                      "name": "Test Key"
                    }
                    """;

            var request = mapper.readValue(json, CreateApiKeyRequest.class);
            assertEquals("Test Key", request.name());
            assertNull(request.permissions());
            assertNull(request.expiresAt());
        }
    }

    // ── ApiKeyResponse.fromEntity ──
    @Nested
    @DisplayName("ApiKeyResponse.fromEntity")
    class FromEntity {

        @Test
        @DisplayName("mapea campos básicos sin rawKey")
        void mapsBasicFields() {
            var key = createTestApiKey();
            var response = ApiKeyResponse.fromEntity(key);

            assertEquals(key.id, response.id());
            assertEquals("k49", response.keyPrefix());
            assertEquals("ERP Production Key", response.name());
            assertEquals("*", response.permissions());
            assertEquals("active", response.status());
            assertNull(response.rawKey(), "rawKey no debe incluirse en fromEntity");
        }

        @Test
        @DisplayName("incluye expiresAt y lastUsedAt cuando presentes")
        void includesOptionalTimestamps() {
            var key = createTestApiKey();
            key.expiresAt = Instant.parse("2027-06-01T00:00:00Z");
            key.lastUsedAt = Instant.parse("2026-04-05T10:00:00Z");

            var response = ApiKeyResponse.fromEntity(key);
            assertEquals(Instant.parse("2027-06-01T00:00:00Z"), response.expiresAt());
            assertEquals(Instant.parse("2026-04-05T10:00:00Z"), response.lastUsedAt());
        }

        @Test
        @DisplayName("status revoked se refleja correctamente")
        void reflectsRevokedStatus() {
            var key = createTestApiKey();
            key.status = "revoked";

            var response = ApiKeyResponse.fromEntity(key);
            assertEquals("revoked", response.status());
        }
    }

    // ── ApiKeyResponse.fromCreated ──
    @Nested
    @DisplayName("ApiKeyResponse.fromCreated")
    class FromCreated {

        @Test
        @DisplayName("incluye rawKey en la respuesta de creación")
        void includesRawKey() {
            var key = createTestApiKey();
            var rawKey = "k49_ABCDEFGHIJKLMNOPqrstuvwx";

            var response = ApiKeyResponse.fromCreated(key, rawKey);
            assertNotNull(response.rawKey());
            assertEquals(rawKey, response.rawKey());
            assertEquals(key.id, response.id());
        }
    }

    // ── Serialización JSON ──
    @Nested
    @DisplayName("Serialización JSON")
    class Serialization {

        @Test
        @DisplayName("serializa ApiKeyResponse con snake_case")
        void serializesWithSnakeCase() throws Exception {
            var key = createTestApiKey();
            var response = ApiKeyResponse.fromEntity(key);

            var json = mapper.writeValueAsString(response);
            assertTrue(json.contains("\"key_prefix\":\"k49\""));
            assertTrue(json.contains("\"status\":\"active\""));
            assertFalse(json.contains("\"raw_key\""), "rawKey null no debe serializarse");
        }

        @Test
        @DisplayName("serializa rawKey en creación")
        void serializesRawKeyOnCreation() throws Exception {
            var key = createTestApiKey();
            var response = ApiKeyResponse.fromCreated(key, "k49_ABCDEF");

            var json = mapper.writeValueAsString(response);
            assertTrue(json.contains("\"raw_key\":\"k49_ABCDEF\""));
        }

        @Test
        @DisplayName("omite campos null gracias a NON_NULL")
        void omitsNullFields() throws Exception {
            var key = createTestApiKey();
            var response = ApiKeyResponse.fromEntity(key);

            var json = mapper.writeValueAsString(response);
            assertFalse(json.contains("\"last_used_at\""));
            assertFalse(json.contains("\"expires_at\""));
            assertFalse(json.contains("\"raw_key\""));
        }
    }
}
