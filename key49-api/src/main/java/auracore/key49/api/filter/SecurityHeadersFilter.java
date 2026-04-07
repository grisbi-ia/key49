package auracore.key49.api.filter;

import org.jboss.resteasy.reactive.server.ServerResponseFilter;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerResponseContext;

/**
 * Filtro que agrega headers de seguridad HTTP a todas las respuestas.
 *
 * <p>
 * Incluye protecciones OWASP recomendadas: clickjacking, sniffing, XSS, HSTS y
 * referrer policy.</p>
 */
@ApplicationScoped
public class SecurityHeadersFilter {

    @ServerResponseFilter
    public void addSecurityHeaders(ContainerResponseContext responseContext) {
        var headers = responseContext.getHeaders();

        // Prevenir clickjacking
        headers.putSingle("X-Frame-Options", "DENY");

        // Prevenir MIME sniffing
        headers.putSingle("X-Content-Type-Options", "nosniff");

        // Referrer policy restrictiva
        headers.putSingle("Referrer-Policy", "strict-origin-when-cross-origin");

        // Permisos del navegador
        headers.putSingle("Permissions-Policy", "camera=(), microphone=(), geolocation=()");

        // Content-Security-Policy para el portal web
        headers.putSingle("Content-Security-Policy",
                "default-src 'self'; "
                + "script-src 'self'; "
                + "style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data:; "
                + "font-src 'self'; "
                + "connect-src 'self'; "
                + "frame-ancestors 'none'");
    }
}
