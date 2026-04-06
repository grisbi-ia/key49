package auracore.key49.api.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;

import org.hibernate.reactive.mutiny.Mutiny;

import auracore.key49.api.dto.MetricsSummaryResponse;
import auracore.key49.api.dto.MetricsSummaryResponse.PeriodSnapshot;
import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.core.tenant.TenantContext;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Servicio de métricas para el dashboard del tenant autenticado.
 * Consulta conteos de documentos agrupados por estado para hoy y el mes actual.
 */
@ApplicationScoped
public class MetricsService {

    @Inject
    TenantContext tenantContext;

    @Inject
    TenantConnectionManager tcm;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    Mutiny.SessionFactory sessionFactory;

    public Uni<MetricsSummaryResponse> getSummary() {
        var schemaName = tenantContext.getSchemaName();
        var tenantId = tenantContext.getTenantId();
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        var monthStart = YearMonth.from(today).atDay(1);

        return tcm.withTenantSession(schemaName, session ->
                countByDateRange(session, today, today)
                        .chain(todayCounts -> countByDateRange(session, monthStart, today)
                                .chain(monthCounts -> findLastAuthorizationDate(session)
                                        .map(lastInvoiceAt -> new PendingResult(
                                                toSnapshot(todayCounts),
                                                toSnapshot(monthCounts),
                                                lastInvoiceAt))))
        ).chain(result ->
                sessionFactory.withSession(s -> tenantRepository.findById(tenantId))
                        .map(tenant -> {
                            long certDays = -1;
                            if (tenant != null && tenant.certificateExpiration != null
                                    && tenant.certificateExpiration.isAfter(Instant.now())) {
                                certDays = Duration.between(Instant.now(), tenant.certificateExpiration).toDays();
                            }
                            Log.infof("Metrics summary | tenantId=%s today=%d month=%d",
                                    tenantId, result.today.total(), result.month.total());
                            return new MetricsSummaryResponse(
                                    result.today, result.month, certDays, result.lastInvoiceAt);
                        })
        );
    }

    private Uni<Map<DocumentStatus, Long>> countByDateRange(Mutiny.Session session,
                                                             LocalDate from, LocalDate to) {
        return session.createQuery(
                        "SELECT d.status, COUNT(d) FROM Document d " +
                                "WHERE d.issueDate >= :from AND d.issueDate <= :to GROUP BY d.status",
                        Object[].class)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList()
                .map(rows -> {
                    var counts = new EnumMap<DocumentStatus, Long>(DocumentStatus.class);
                    for (var row : rows) {
                        counts.put((DocumentStatus) row[0], (Long) row[1]);
                    }
                    return counts;
                });
    }

    private Uni<Instant> findLastAuthorizationDate(Mutiny.Session session) {
        return session.createQuery(
                        "SELECT MAX(d.authorizationDate) FROM Document d " +
                                "WHERE d.authorizationDate IS NOT NULL", Instant.class)
                .getSingleResultOrNull();
    }

    private PeriodSnapshot toSnapshot(Map<DocumentStatus, Long> counts) {
        long authorized = counts.getOrDefault(DocumentStatus.AUTHORIZED, 0L)
                + counts.getOrDefault(DocumentStatus.NOTIFIED, 0L)
                + counts.getOrDefault(DocumentStatus.VOIDED, 0L);
        long rejected = counts.getOrDefault(DocumentStatus.REJECTED, 0L);
        long failed = counts.getOrDefault(DocumentStatus.FAILED, 0L);
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long pending = total - authorized - rejected - failed;
        return new PeriodSnapshot(total, authorized, rejected, pending, failed);
    }

    private record PendingResult(PeriodSnapshot today, PeriodSnapshot month, Instant lastInvoiceAt) {}
}
