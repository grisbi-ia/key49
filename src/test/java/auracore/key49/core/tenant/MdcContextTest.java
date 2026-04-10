package auracore.key49.core.tenant;

import org.jboss.logging.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para MdcContext.
 */
class MdcContextTest {

    @AfterEach
    void tearDown() {
        MdcContext.clear();
    }

    @Test
    void shouldSetTenantInMdc() {
        MdcContext.setTenant("tenant_abc");
        assertEquals("tenant_abc", MDC.get(MdcContext.TENANT_KEY));
    }

    @Test
    void shouldSetDocumentInMdc() {
        var docId = UUID.randomUUID();
        MdcContext.setDocument(docId);
        assertEquals(docId.toString(), MDC.get(MdcContext.DOCUMENT_KEY));
    }

    @Test
    void shouldSetDocumentFromString() {
        MdcContext.setDocument("doc-123");
        assertEquals("doc-123", MDC.get(MdcContext.DOCUMENT_KEY));
    }

    @Test
    void shouldClearAllMdcFields() {
        MdcContext.setTenant("tenant_abc");
        MdcContext.setDocument(UUID.randomUUID());

        MdcContext.clear();

        assertNull(MDC.get(MdcContext.TENANT_KEY));
        assertNull(MDC.get(MdcContext.DOCUMENT_KEY));
    }

    @Test
    void shouldIgnoreNullTenant() {
        MdcContext.setTenant(null);
        assertNull(MDC.get(MdcContext.TENANT_KEY));
    }

    @Test
    void shouldIgnoreNullDocument() {
        MdcContext.setDocument(null);
        assertNull(MDC.get(MdcContext.DOCUMENT_KEY));
    }

    @Test
    void shouldOverwriteExistingValues() {
        MdcContext.setTenant("tenant_abc");
        MdcContext.setTenant("tenant_xyz");
        assertEquals("tenant_xyz", MDC.get(MdcContext.TENANT_KEY));
    }
}
