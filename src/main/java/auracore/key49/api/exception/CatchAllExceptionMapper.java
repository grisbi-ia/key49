package auracore.key49.api.exception;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Catch-all exception mapper for unhandled exceptions. Returns a generic error
 * response without leaking internal details.
 */
public class CatchAllExceptionMapper {

    private static final Logger log = Logger.getLogger(CatchAllExceptionMapper.class);

    @ServerExceptionMapper
    public Response handleNotFound(NotFoundException ex) {
        log.debugf("Resource not found: %s", ex.getMessage());
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity("{\"error\":{\"code\":\"NOT_FOUND\",\"message\":\"Resource not found\"}}")
                .build();
    }

    @ServerExceptionMapper(priority = Integer.MAX_VALUE)
    public Response handleUnexpected(Throwable ex) {
        log.errorf(ex, "Unhandled exception: %s", ex.getMessage());
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity("{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"An unexpected error occurred\"}}")
                .build();
    }
}
