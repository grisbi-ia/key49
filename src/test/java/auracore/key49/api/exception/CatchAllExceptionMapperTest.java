package auracore.key49.api.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.core.Response;

class CatchAllExceptionMapperTest {

    private final CatchAllExceptionMapper mapper = new CatchAllExceptionMapper();

    @Test
    @DisplayName("retorna 500 Internal Server Error")
    void returns500() {
        Response response = mapper.handleUnexpected(new RuntimeException("boom"));
        assertEquals(500, response.getStatus());
    }

    @Test
    @DisplayName("cuerpo contiene código INTERNAL_ERROR")
    void bodyContainsErrorCode() {
        Response response = mapper.handleUnexpected(new RuntimeException("boom"));
        var entity = response.getEntity();
        assertNotNull(entity);
        var body = entity.toString();
        assertEquals("{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"An unexpected error occurred\"}}", body);
    }

    @Test
    @DisplayName("no expone mensaje de la excepción original")
    void doesNotExposeOriginalMessage() {
        Response response = mapper.handleUnexpected(new RuntimeException("database password is abc123"));
        var body = response.getEntity().toString();
        assertEquals(-1, body.indexOf("abc123"), "Response body must not contain exception message");
    }

    @Test
    @DisplayName("respuesta tiene MediaType JSON")
    void hasJsonMediaType() {
        Response response = mapper.handleUnexpected(new NullPointerException());
        assertEquals("application/json", response.getMediaType().toString());
    }
}
