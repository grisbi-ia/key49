package auracore.key49.admin.alert.rules;

import auracore.key49.admin.alert.AlertResult;
import auracore.key49.admin.alert.AlertRule;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantConnectionManager;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Regla de alerta SLA: latencia de autorización.
 *
 * <p>
 * Verifica que los documentos no permanezcan sin autorizar más allá del umbral
 * configurado (por defecto 5 minutos). Itera todos los tenants activos y
 * consulta documentos en estados intermedios (CREATED, SIGNED, SENT, RECEIVED)
 * cuyo {@code created_at} supere el tiempo máximo permitido.</p>
 *
 * <p>
 * Por cada tenant con incumplimiento se incrementa la métrica
 * {@code key49.sla.breach{tenant, type=authorization_latency}}.</p>
 */
@ApplicationScoped
public class SlaAuthorizationAlertRule implements AlertRule {

    static final String NAME = "sla_authorization";
    private static final Logger log = Logger.getLogger(SlaAuthorizationAlertRule.class);

    private static final String STUCK_DOCS_QUERY = """
            SELECT count(*) FROM documents
            WHERE status IN ('CREATED', 'SIGNED', 'SENT', 'RECEIVED')
              AND created_at < (now() - make_interval(mins => :minutes))
            """;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    TenantConnectionManager tenantConnectionManager;

    @Inject
    MeterRegistry registry;

    @ConfigProperty(name = "key49.alerts.sla-authorization-minutes", defaultValue = "5")
    int slaAuthorizationMinutes;

    @Override
    public AlertResult evaluate() {
        try {
            var tenants = tenantRepository.findAllActive();
            if (tenants.isEmpty()) {
                return AlertResult.ok(NAME, "Sin tenants activos");
            }

            List<String> breaching = new ArrayList<>();

            for (var tenant : tenants) {
                var count = countStuckDocuments(tenant.schemaName);
                if (count > 0) {
                    breaching.add("%s(%d)".formatted(tenant.schemaName, count));
                    registry.counter("key49.sla.breach",
                            "tenant", tenant.schemaName,
                            "type", "authorization_latency").increment(count);
                }
            }

            if (!breaching.isEmpty()) {
                return AlertResult.firing(NAME,
                        "Documentos sin autorizar > %d min en: %s"
                                .formatted(slaAuthorizationMinutes, String.join(", ", breaching)));
            }

            return AlertResult.ok(NAME,
                    "Todos los documentos dentro del SLA (%d min)".formatted(slaAuthorizationMinutes));
        } catch (Exception e) {
            log.warnf("SLA authorization alert eval failed: %s", e.getMessage());
            return AlertResult.ok(NAME, "No se pudo evaluar: " + e.getMessage());
        }
    }

    long countStuckDocuments(String schemaName) {
        return tenantConnectionManager.withTenantSession(schemaName, em -> {
            var result = em.createNativeQuery(STUCK_DOCS_QUERY)
                    .setParameter("minutes", slaAuthorizationMinutes)
                    .getSingleResult();
            return ((Number) result).longValue();
        });
    }
}
