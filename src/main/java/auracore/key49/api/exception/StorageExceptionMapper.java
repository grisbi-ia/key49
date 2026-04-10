package auracore.key49.api.exception;

import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import auracore.key49.storage.StorageException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Mapper que convierte errores de almacenamiento y circuit breaker de MinIO en
 * respuestas HTTP apropiadas.
 */
public class StorageExceptionMapper {

    @ServerExceptionMapper
    public Response handleStorageException(StorageException ex) {
        var body = new ErrorBody(new ErrorBody.Detail(
                "STORAGE_ERROR", "Storage service unavailable: " + ex.getMessage()));
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(body)
                .build();
    }

    @ServerExceptionMapper
    public Response handleCircuitBreakerOpen(CircuitBreakerOpenException ex) {
        var body = new ErrorBody(new ErrorBody.Detail(
                "SERVICE_UNAVAILABLE", "Service temporarily unavailable, please retry later"));
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(body)
                .build();
    }

    record ErrorBody(Detail error) {

        record Detail(String code, String message) {

        }
    }
}
