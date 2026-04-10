package auracore.key49.api.resource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import auracore.key49.api.dto.AuditLogResponse;
import auracore.key49.api.dto.PagedResponse;
import auracore.key49.core.repository.AuditLogRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Endpoint de administración para consulta del audit log. Protegido por
 * AdminAuthFilter (X-Admin-Token).
 */
@Path("/v1/admin/audit-log")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuditLogAdminResource {

    @Inject
    AuditLogRepository auditLogRepository;

    @GET
    public Response list(
            @QueryParam("tenant_id") UUID tenantId,
            @QueryParam("action") String action,
            @QueryParam("date_from") String dateFrom,
            @QueryParam("date_to") String dateTo,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("per_page") @DefaultValue("50") int perPage) {

        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        Instant from = parseDate(dateFrom, false);
        Instant to = parseDate(dateTo, true);

        var items = auditLogRepository.findFiltered(tenantId, action, from, to, page, perPage);
        var total = auditLogRepository.countFiltered(tenantId, action, from, to);

        var responses = items.stream()
                .map(AuditLogResponse::fromEntity)
                .toList();
        var body = PagedResponse.of(responses, total, page, perPage);
        return Response.ok()
                .header("X-Request-Id", requestId)
                .entity(body)
                .build();
    }

    private static Instant parseDate(String dateStr, boolean endOfDay) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        var date = LocalDate.parse(dateStr);
        return endOfDay
                ? date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                : date.atStartOfDay().toInstant(ZoneOffset.UTC);
    }
}
                    