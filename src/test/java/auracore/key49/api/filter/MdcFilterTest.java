package auracore.key49.api.filter;

import auracore.key49.api.portal.PortalAuthFilter;
import auracore.key49.api.portal.PortalSessionService;
import auracore.key49.core.tenant.MdcContext;
import auracore.key49.core.tenant.TenantContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitarios para MdcFilter.
 */
class MdcFilterTest {

    private MdcFilter filter;
    private TenantContext tenantContext;
    private ContainerRequestContext requestContext;

    @BeforeEach
    void setUp() {
        filter = new MdcFilter();
        tenantContext = new TenantContext();
        filter.tenantContext = tenantContext;
        requestContext = mock(ContainerRequestContext.class);
    }

    @AfterEach
    void tearDown() {
        MdcContext.clear();
    }

    @Test
    void shouldSetTenantFromApiKeyAuth() {
        tenantContext.setTenant(UUID.randomUUID(), "tenant_abc");

        filter.setMdc(requestContext);

        assertEquals("tenant_abc", MDC.get(MdcContext.TENANT_KEY));
    }

    @Test
    void shouldSetTenantFromPortalSession() {
        var session = new PortalSessionService.PortalSession(
                UUID.randomUUID(), "tenant_portal", "Test Corp");
        when(requestContext.getProperty(PortalAuthFilter.PORTAL_SESSION_ATTR))
                .thenReturn(session);

        filter.setMdc(requestContext);

        assertEquals("tenant_portal", MDC.get(MdcContext.TENANT_KEY));
    }

    @Test
    void shouldPreferApiKeyOverPortalSession() {
        tenantContext.setTenant(UUID.randomUUID(), "tenant_api");
        var session = new PortalSessionService.PortalSession(
                UUID.randomUUID(), "tenant_portal", "Test Corp");
        when(requestContext.getProperty(PortalAuthFilter.PORTAL_SESSION_ATTR))
                .thenReturn(session);

        filter.setMdc(requestContext);

        assertEquals("tenant_api", MDC.get(MdcContext.TENANT_KEY));
    }

    @Test
    void shouldNotSetMdcWhenNoAuth() {
        filter.setMdc(requestContext);

        assertNull(MDC.get(MdcContext.TENANT_KEY));
    }

    @Test
    void shouldClearMdcOnResponse() {
        MdcContext.setTenant("tenant_abc");
        MdcContext.setDocument(UUID.randomUUID());

        filter.clearMdc();

        assertNull(MDC.get(MdcContext.TENANT_KEY));
        assertNull(MDC.get(MdcContext.DOCUMENT_KEY));
    }
}
  