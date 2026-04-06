package auracore.key49.api.exception;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import auracore.key49.core.service.ApiKeyManagementService.ApiKeyException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Mapper de excepciones de gestión de API keys a respuestas HTTP.
 */
public class ApiKeyExceptionMapper {

    @ServerExceptionMapper
    public Response mapApiKeyException(ApiKeyException ex) {
        record ErrorDetail(String code, String message) {

        }
        record ErrorWrapper(ErrorDetail error) {

        }
        return Response.status(ex.httpStatus())
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorWrapper(new ErrorDetail(ex.code(), ex.getMessage())))
                .build();
    }
}
