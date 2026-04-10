package auracore.key49.api.exception;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Catch-all exception mapper for unhandled exceptions. Returns a generic error
 * response without leaking internal details.
 */
public class CatchAllExceptionMapper {

    private static final Logger log = Logger.getLogger(CatchAllExceptionMapper.class);

    @ServerExceptionMapper(priority = Integer.MAX_VALUE)
    public Response handleUnexpected(Throwable ex) {
        log.errorf(ex, "Unhandled exception: %s", ex.getMessage());
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity("{\"error\":{\"code\":\"INTERNAL_ERROR\",\"message\":\"An unexpected error occurred\"}}")
                .build();
    }
}
