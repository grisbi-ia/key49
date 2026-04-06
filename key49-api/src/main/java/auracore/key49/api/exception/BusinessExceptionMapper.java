package auracore.key49.api.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.util.List;

/**
 * Mapper que convierte BusinessException en el formato de error estándar de Key49.
 */
public class BusinessExceptionMapper {

    @ServerExceptionMapper
    public Response handleBusinessException(BusinessException ex) {
        var body = new ErrorWrapper(new ErrorDetail(
                ex.code(),
                ex.getMessage(),
                ex.details().isEmpty() ? null : ex.details()));

        return Response.status(ex.httpStatus())
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(body)
                .build();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record ErrorDetail(String code, String message, List<BusinessException.FieldError> details) {
    }

    record ErrorWrapper(ErrorDetail error) {
    }
}
