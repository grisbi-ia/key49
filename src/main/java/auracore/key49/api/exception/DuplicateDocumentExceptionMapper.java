package auracore.key49.api.exception;

import java.time.Instant;
import java.util.UUID;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Mapper que convierte DuplicateDocumentException en una respuesta 409
 * informativa con los datos del documento existente.
 */
public class DuplicateDocumentExceptionMapper {

    @ServerExceptionMapper
    public Response handleDuplicateDocument(DuplicateDocumentException ex) {
        var body = new ErrorWrapper(new ErrorDetail(
                ex.code(),
                ex.getMessage(),
                new ExistingDocument(
                        ex.existingDocumentId(),
                        ex.existingStatus(),
                        ex.accessKey(),
                        ex.authorizationDate())));

        return Response.status(409)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(body)
                .build();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ExistingDocument(UUID id, String status, String accessKey, Instant authorizationDate) {

    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErrorDetail(String code, String message, ExistingDocument existingDocument) {

    }

    record ErrorWrapper(ErrorDetail error) {

    }
}
