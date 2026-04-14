package auracore.key49.core.service;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import auracore.key49.core.service.ApiKeyManagementService.ApiKeyException;
import auracore.key49.core.service.ApiKeyManagementService.CreateApiKeyData;

/**
 * Tests unitarios para validaciones de ApiKeyManagementService.
 */
class ApiKeyManagementServiceTest {

    private final ApiKeyManagementService service = new ApiKeyManagementService();

    // ── Validación de CreateApiKeyData ──
    @Nested
    @DisplayName("create — validación de datos")
    class CreateValidation {

        @Test
        @DisplayName("nombre null lanza excepción")
        void nameNull() {
            var data = new CreateApiKeyData(null, null, null);
            var ex = assertThrows(ApiKeyException.class,
                    () -> service.create(java.util.UUID.randomUUID(), data));
            assertEquals("VALIDATION_ERROR", ex.code());
            assertEquals(400, ex.httpStatus());
            assertTrue(ex.getMessage().contains("name is required"));
        }

        @Test
        @DisplayName("nombre vacío lanza excepción")
        void nameEmpty() {
            var data = new CreateApiKeyData("  ", null, null);
            var ex = assertThrows(ApiKeyException.class,
                    () -> service.create(java.util.UUID.randomUUID(), data));
            assertEquals("VALIDATION_ERROR", ex.code());
            assertTrue(ex.getMessage().contains("name is required"));
        }

        @Test
        @DisplayName("nombre mayor a 100 chars lanza excepción")
        void nameTooLong() {
            var longName = "A".repeat(101);
            var data = new CreateApiKeyData(longName, null, null);
            var ex = assertThrows(ApiKeyException.class,
                    () -> service.create(java.util.UUID.randomUUID(), data));
            assertEquals("VALIDATION_ERROR", ex.code());
            assertTrue(ex.getMessage().contains("max 100"));
        }

        @Test
        @DisplayName("nombre válido pasa la validación")
        void validNamePassesValidation() {
            var data = new CreateApiKeyData("Key 1", null, null);
            // Will fail at persistence (no repository injected), but validation passes
            assertThrows(NullPointerException.class,
                    () -> service.create(java.util.UUID.randomUUID(), data));
        }
    }

    // ── CreateApiKeyData record ──
    @Nested
    @DisplayName("CreateApiKeyData record")
    class DataRecord {

        @Test
        @DisplayName("preserva todos los campos")
        void preservesFields() {
            var exp = Instant.parse("2027-01-01T00:00:00Z");
            var data = new CreateApiKeyData("ERP Key", "invoices:write", exp);

            assertEquals("ERP Key", data.name());
            assertEquals("invoices:write", data.permissions());
            assertEquals(exp, data.expiresAt());
        }

        @Test
        @DisplayName("expiresAt null es válido")
        void expiresAtNullValid() {
            var data = new CreateApiKeyData("Key", null, null);
            assertNotNull(data);
            assertEquals("Key", data.name());
        }
    }

    // ── ApiKeyException ──
    @Nested
    @DisplayName("ApiKeyException")
    class ExceptionTests {

        @Test
        @DisplayName("contiene code, message y httpStatus")
        void exceptionFields() {
            var ex = new ApiKeyException("TEST_CODE", "Test message", 404);
            assertEquals("TEST_CODE", ex.code());
            assertEquals("Test message", ex.getMessage());
            assertEquals(404, ex.httpStatus());
        }

        @Test
        @DisplayName("ALREADY_REVOKED con status 409")
        void alreadyRevoked() {
            var ex = new ApiKeyException("ALREADY_REVOKED", "API key is already revoked", 409);
            assertEquals("ALREADY_REVOKED", ex.code());
            assertEquals(409, ex.httpStatus());
        }
    }
}
