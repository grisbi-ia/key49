package auracore.key49.api.exception;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BusinessExceptionTest {

    @Test
    void constructorWithoutDetails() {
        var ex = new BusinessException("NOT_FOUND", "Document not found", 404);

        assertEquals("NOT_FOUND", ex.code());
        assertEquals("Document not found", ex.getMessage());
        assertEquals(404, ex.httpStatus());
        assertTrue(ex.details().isEmpty());
    }

    @Test
    void constructorWithDetails() {
        var details = List.of(
                new BusinessException.FieldError("field1", "required", "REQUIRED"),
                new BusinessException.FieldError("field2", "invalid format", "INVALID_FORMAT"));

        var ex = new BusinessException("VALIDATION_ERROR", "Invalid request", 400, details);

        assertEquals("VALIDATION_ERROR", ex.code());
        assertEquals(400, ex.httpStatus());
        assertEquals(2, ex.details().size());
        assertEquals("field1", ex.details().getFirst().field());
    }

    @Test
    void detailsList_isImmutable() {
        var details = List.of(new BusinessException.FieldError("f", "m", "c"));
        var ex = new BusinessException("ERR", "msg", 400, details);

        assertThrows(UnsupportedOperationException.class,
                () -> ex.details().add(new BusinessException.FieldError("x", "y", "z")));
    }

    @Test
    void nullDetails_treatedAsEmpty() {
        var ex = new BusinessException("ERR", "msg", 400, null);

        assertNotNull(ex.details());
        assertTrue(ex.details().isEmpty());
    }
}
