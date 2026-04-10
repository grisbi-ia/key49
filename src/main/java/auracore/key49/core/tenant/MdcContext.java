package auracore.key49.core.tenant;

import org.jboss.logging.MDC;

/**
 * Utilidad para gestionar campos MDC (Mapped Diagnostic Context) de tenant y
 * documento en logs estructurados. Centraliza las claves MDC para garantizar
 * consistencia en HTTP filters, consumers y schedulers.
 */
public final class MdcContext {

    public static final String TENANT_KEY = "tenant";
    public static final String DOCUMENT_KEY = "documentId";

    private MdcContext() {
    }

    public static void setTenant(String tenant) {
        if (tenant != null) {
            MDC.put(TENANT_KEY, tenant);
        }
    }

    public static void setDocument(Object documentId) {
        if (documentId != null) {
            MDC.put(DOCUMENT_KEY, documentId.toString());
        }
    }

    public static void clear() {
        MDC.remove(TENANT_KEY);
        MDC.remove(DOCUMENT_KEY);
    }
}
