package auracore.key49.api.portal;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

import java.net.URI;

/**
 * Filtro de autenticación para el portal web.
 *
 * <p>
 * Valida la cookie {@code KEY49_SESSION} contra Redis en todas las rutas
 * {@code /portal/*} excepto {@code /portal/login}. Redirige al login si la
 * sesión no es válida.</p>
 */
public class PortalAuthFilter {

    static final String SESSION_COOKIE = "KEY49_SESSION";
    static final String PORTAL_SESSION_ATTR = "key49.portal.session";

    @Inject
    Logger log;

    @Inject
    PortalSessionService sessionService;

    @ServerRequestFilter(priority = 15)
    public Response filterPortal(ContainerRequestContext ctx) {
        var path = ctx.getUriInfo().getPath();

        if (!path.startsWith("/portal") || path.startsWith("/portal/login")) {
            return null;
        }

        var cookies = ctx.getCookies();
        var sessionCookie = cookies.get(SESSION_COOKIE);
        if (sessionCookie == null || sessionCookie.getValue().isBlank()) {
            return redirectToLogin();
        }

        var session = sessionService.validate(sessionCookie.getValue());
        if (session == null) {
            return redirectToLogin();
        }

        ctx.setProperty(PORTAL_SESSION_ATTR, session);
        return null;
    }

    private Response redirectToLogin() {
        return Response.seeOther(URI.create("/portal/login"))
                .cookie(new NewCookie.Builder(SESSION_COOKIE)
                        .value("")
                        .path("/portal")
                        .maxAge(0)
                        .httpOnly(true)
                        .build())
                .build();
    }
}
