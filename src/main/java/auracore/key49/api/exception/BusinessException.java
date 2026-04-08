package auracore.key49.api.exception;

import java.util.List;

/**
 * Excepción para errores de negocio que se mapean a respuestas HTTP con código y mensaje.
 */
public class BusinessException extends RuntimeException {

    private final String code;
    private final int httpStatus;
    private final List<FieldError> details;

    public BusinessException(String code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = List.of();
    }

    public BusinessException(String code, String message, int httpStatus, List<FieldError> details) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
        this.details = details != null ? List.copyOf(details) : List.of();
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public List<FieldError> details() {
        return details;
    }

    public record FieldError(String field, String message, String code) {
    }
}
