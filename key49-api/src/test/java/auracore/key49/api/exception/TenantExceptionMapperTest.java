package auracore.key49.api.exception;

import auracore.key49.core.service.TenantAdminService.TenantException;
import jakarta.ws.rs.core.Response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitarios para TenantExceptionMapper.
 */
class TenantExceptionMapperTest {

    private final TenantExceptionMapper mapper = new TenantExceptionMapper();

    @Test
    @DisplayName("mapea TenantException 404 correctamente")
    void maps404() {
        var ex = new TenantException("TENANT_NOT_FOUND", "Tenant not found", 404);
        Response response = mapper.handleTenantException(ex);

        assertEquals(404, response.getStatus());
        assertNotNull(response.getEntity());
    }

    @Test
    @DisplayName("mapea TenantException 409 correctamente")
    void maps409() {
        var ex = new TenantException("DUPLICATE_RUC", "Duplicate RUC", 409);
        Response response = mapper.handleTenantException(ex);

        assertEquals(409, response.getStatus());
    }

    @Test
    @DisplayName("mapea TenantException 400 correctamente")
    void maps400() {
        var ex = new TenantException("VALIDATION_ERROR", "Invalid data", 400);
        Response response = mapper.handleTenantException(ex);

        assertEquals(400, response.getStatus());
    }

    @Test
    @DisplayName("respuesta tiene MediaType JSON")
    void hasJsonMediaType() {
        var ex = new TenantException("TEST", "test", 500);
        Response response = mapper.handleTenantException(ex);

        assertEquals("application/json", response.getMediaType().toString());
    }
}
