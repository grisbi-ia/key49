package auracore.key49.api.exception;

import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import auracore.key49.storage.StorageException;
import jakarta.ws.rs.core.Response;

/**
 * Tests unitarios para StorageExceptionMapper.
 */
class StorageExceptionMapperTest {

    private final StorageExceptionMapper mapper = new StorageExceptionMapper();

    @Test
    @DisplayName("StorageException retorna 503 Service Unavailable")
    void storageExceptionReturns503() {
        var ex = new StorageException("Connection refused");
        Response response = mapper.handleStorageException(ex);

        assertEquals(503, response.getStatus());
        assertNotNull(response.getEntity());
    }

    @Test
    @DisplayName("StorageException incluye código STORAGE_ERROR")
    void storageExceptionIncludesErrorCode() {
        var ex = new StorageException("Connection refused");
        Response response = mapper.handleStorageException(ex);

        var body = (StorageExceptionMapper.ErrorBody) response.getEntity();
        assertEquals("STORAGE_ERROR", body.error().code());
    }

    @Test
    @DisplayName("CircuitBreakerOpenException retorna 503")
    void circuitBreakerOpenReturns503() {
        var ex = new CircuitBreakerOpenException("Circuit open");
        Response response = mapper.handleCircuitBreakerOpen(ex);

        assertEquals(503, response.getStatus());
        assertNotNull(response.getEntity());
    }

    @Test
    @DisplayName("CircuitBreakerOpenException incluye código SERVICE_UNAVAILABLE")
    void circuitBreakerOpenIncludesErrorCode() {
        var ex = new CircuitBreakerOpenException("Circuit open");
        Response response = mapper.handleCircuitBreakerOpen(ex);

        var body = (StorageExceptionMapper.ErrorBody) response.getEntity();
        assertEquals("SERVICE_UNAVAILABLE", body.error().code());
    }

    @Test
    @DisplayName("respuesta tiene MediaType JSON")
    void hasJsonMediaType() {
        var ex = new StorageException("test");
        Response response = mapper.handleStorageException(ex);

        assertEquals("application/json", response.getMediaType().toString());
    }
}
