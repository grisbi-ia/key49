package auracore.key49.api.exception;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import auracore.key49.core.service.TenantAdminService.TenantException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Mapper que convierte TenantException en el formato de error estándar de
 * Key49.
 */
public class TenantExceptionMapper {

    @ServerExceptionMapper
    public Response handleTenantException(TenantException ex) {
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
