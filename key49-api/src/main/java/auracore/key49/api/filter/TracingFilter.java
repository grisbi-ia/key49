package auracore.key49.api.filter;

import io.opentelemetry.api.trace.Span;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext;

import java.util.UUID;

/**
 * Filtro que agrega headers de trazabilidad a todas las respuestas HTTP.
 *
 * <p>Genera un {@code X-Request-Id} único por request (formato {@code req_{16chars}})
 * y extrae el {@code X-Trace-Id} del contexto de OpenTelemetry si está disponible.</p>
 *
 * <p>Priority 5: se ejecuta antes del filtro de autenticación (10), para que
 * el request ID esté disponible desde el inicio del procesamiento.</p>
 */
@ApplicationScoped
public class TracingFilter {

    private static final Logger log = Logger.getLogger(TracingFilter.class);
    private static final String REQUEST_ID_PROPERTY = "key49.requestId";
    private static final String TRACE_ID_PROPERTY = "key49.traceId";

    @ServerRequestFilter(priority = 5)
    public void captureTracing(ResteasyReactiveContainerRequestContext requestContext) {
        var requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        requestContext.setProperty(REQUEST_ID_PROPERTY, requestId);

        var span = Span.current();
        if (span != null && span.getSpanContext().isValid()) {
            var traceId = span.getSpanContext().getTraceId();
            requestContext.setProperty(TRACE_ID_PROPERTY, traceId);
        }
    }

    @ServerResponseFilter
    public void addTracingHeaders(ResteasyReactiveContainerRequestContext requestContext,
                                  jakarta.ws.rs.container.ContainerResponseContext responseContext) {
        var requestId = requestContext.getProperty(REQUEST_ID_PROPERTY);
        if (requestId != null && responseContext.getHeaderString("X-Request-Id") == null) {
            responseContext.getHeaders().putSingle("X-Request-Id", requestId);
        }

        var traceId = requestContext.getProperty(TRACE_ID_PROPERTY);
        if (traceId != null) {
            responseContext.getHeaders().putSingle("X-Trace-Id", traceId);
        }
    }
}
