package auracore.key49.api.filter;

import auracore.key49.api.portal.PortalAuthFilter;
import auracore.key49.api.portal.PortalSessionService;
import auracore.key49.core.tenant.MdcContext;
import auracore.key49.core.tenant.TenantContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;

/**
 * Filtro que establece campos MDC (tenant, documentId) para structured logging.
 *
 * <p>Priority 25: se ejecuta después de todos los filtros de autenticación
 * (ApiKeyAuthFilter=10, PortalAuthFilter=15, AdminAuth/RateLimit=20) para que
 * el contexto del tenant ya esté disponible.</p>
 *
 * <p>Limpia el MDC en la respuesta para evitar fugas entre requests
 * (especialmente en virtual threads reutilizados).</p>
 */
@ApplicationScoped
public class MdcFilter {

    @Inject
    TenantContext tenantContext;

    @ServerRequestFilter(priority = 25)
    public void setMdc(ContainerRequestContext ctx) {
        // API path: TenantContext populated by ApiKeyAuthFilter
        if (tenantContext.isSet()) {
            MdcContext.setTenant(tenantContext.getSchemaName());
            return;
        }

        // Portal path: session stored by PortalAuthFilter
        var session = ctx.getProperty(PortalAuthFilter.PORTAL_SESSION_ATTR);
        if (session instanceof PortalSessionService.PortalSession ps) {
            MdcContext.setTenant(ps.schemaName());
        }
    }

    @ServerResponseFilter
    public void clearMdc() {
        MdcContext.clear();
    }
}
