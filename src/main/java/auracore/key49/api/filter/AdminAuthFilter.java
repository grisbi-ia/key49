package auracore.key49.api.filter;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.security.MessageDigest;
import java.util.Optional;

/**
 * Filtro de autorización para endpoints de administración (/v1/admin/*).
 * Requiere un token de administrador configurado vía variable de entorno. Se
 * ejecuta después del ApiKeyAuthFilter (priority 20).
 */
public class AdminAuthFilter {

    private static final String ADMIN_HEADER = "X-Admin-Token";

    @Inject
    Logger log;

    @ConfigProperty(name = "key49.admin.token")
    Optional<String> adminToken;

    @ServerRequestFilter(priority = 20)
    public Response authorizeAdmin(ContainerRequestContext requestContext) {
        var path = requestContext.getUriInfo().getPath();
        if (!path.startsWith("/v1/admin/")) {
            return null;
        }

        if (adminToken.isEmpty() || adminToken.get().isBlank()) {
            log.error("Admin token not configured — blocking admin access");
            return forbiddenResponse("Admin access not configured");
        }

        var token = requestContext.getHeaderString(ADMIN_HEADER);
        if (token == null || token.isBlank()) {
            return forbiddenResponse("Admin token required");
        }

        if (!timeSafeEquals(adminToken.get(), token)) {
            log.warnf("Invalid admin token from IP=%s", requestContext.getHeaderString("X-Forwarded-For"));
            return forbiddenResponse("Invalid admin token");
        }

        return null;
    }

    private static boolean timeSafeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Response forbiddenResponse(String message) {
        return Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity("{\"error\":{\"code\":\"ADMIN_AUTH_REQUIRED\",\"message\":\"" + message + "\"}}")
                .build();
    }
}
