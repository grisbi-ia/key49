package auracore.key49.api.exception;

import jakarta.validation.ConstraintViolationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.List;

/**
 * Mapper que convierte ConstraintViolationException en el formato de error estándar de Key49.
 */
public class ValidationExceptionMapper {

    @ServerExceptionMapper
    public Response handleConstraintViolation(ConstraintViolationException ex) {
        var details = ex.getConstraintViolations().stream()
                .map(v -> new FieldError(
                        extractFieldName(v.getPropertyPath().toString()),
                        v.getMessage(),
                        "INVALID_FORMAT"))
                .toList();

        var body = new ErrorResponse(
                new ErrorBody("VALIDATION_ERROR", "Request contiene campos inválidos", details));

        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(body)
                .build();
    }

    private String extractFieldName(String path) {
        // PropertyPath format: "methodName.paramName.field" → extract last part
        int lastDot = path.lastIndexOf('.');
        return lastDot >= 0 ? path.substring(lastDot + 1) : path;
    }

    public record FieldError(String field, String message, String code) {
    }

    public record ErrorBody(String code, String message, List<FieldError> details) {
    }

    public record ErrorResponse(ErrorBody error) {
    }
}
