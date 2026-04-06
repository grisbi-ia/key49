package auracore.key49.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wrapper genérico para respuestas exitosas de un solo objeto.
 *
 * <pre>{@code
 * {
 *   "data": { ... },
 *   "meta": { "request_id": "req_xxx", "timestamp": "..." }
 * }
 * }</pre>
 */
public record ApiResponse<T>(T data, Meta meta) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Meta(String requestId, Instant timestamp) {
    }

    public static <T> ApiResponse<T> of(T data, String requestId) {
        return new ApiResponse<>(data, new Meta(requestId, Instant.now()));
    }
}
