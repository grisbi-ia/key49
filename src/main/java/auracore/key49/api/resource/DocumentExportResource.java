package auracore.key49.api.resource;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import auracore.key49.api.exception.BusinessException;
import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.core.tenant.TenantContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.logging.Logger;

/**
 * Endpoint de exportación masiva de documentos en formato CSV.
 */

@Path("/v1/documents")
public class DocumentExportResource {

    static final int MAX_EXPORT_ROWS = 10_000;
    private static final int BATCH_SIZE = 500;
    private static final String CSV_HEADER =
            "access_key,document_type,establishment,issue_point,sequence_number,recipient_id,recipient_name,total_amount,status,issue_date,authorization_date";

    @Inject
    Logger log;

    @Inject
    TenantContext tenantContext;

    @Inject
    TenantConnectionManager tcm;

    /**
     * GET /v1/documents/export — Exportar documentos como CSV con streaming.
     */
    @GET
    @Path("/export")
    @Produces("text/csv")
    public Response export(
            @QueryParam("format") @DefaultValue("csv") String format,
            @QueryParam("from") LocalDate dateFrom,
            @QueryParam("to") LocalDate dateTo,
            @QueryParam("status") String status,
            @QueryParam("document_type") String documentType,
            @QueryParam("recipient_id") String recipientId) {

        if (!"csv".equalsIgnoreCase(format)) {
            throw new BusinessException("VALIDATION_ERROR", "Only CSV format is supported", 400);
        }
        if (dateFrom == null || dateTo == null) {
            throw new BusinessException("VALIDATION_ERROR",
                    "Query parameters 'from' and 'to' are required", 400);
        }
        if (dateTo.isBefore(dateFrom)) {
            throw new BusinessException("VALIDATION_ERROR",
                    "'to' date must be on or after 'from' date", 400);
        }

        var schemaName = tenantContext.getSchemaName();
        var filterSql = new StringBuilder();
        var params = new HashMap<String, Object>();

        params.put("dateFrom", dateFrom);
        params.put("dateTo", dateTo);

        if (status != null && !status.isBlank()) {
            DocumentStatus.valueOf(status); // validate enum
            filterSql.append(" AND d.status = :status");
            params.put("status", DocumentStatus.valueOf(status));
        }
        if (documentType != null && !documentType.isBlank()) {
            filterSql.append(" AND d.documentType = :documentType");
            params.put("documentType", documentType);
        }
        if (recipientId != null && !recipientId.isBlank()) {
            filterSql.append(" AND d.recipientId = :recipientId");
            params.put("recipientId", recipientId);
        }

        String baseHql = "FROM Document d WHERE d.issueDate >= :dateFrom AND d.issueDate <= :dateTo"
                + filterSql;

        // Count first to enforce limit
        long total = tcm.withTenantSession(schemaName, em -> {
            var q = em.createQuery("SELECT count(d) " + baseHql, Long.class);
            params.forEach(q::setParameter);
            return q.getSingleResult();
        });

        if (total > MAX_EXPORT_ROWS) {
            throw new BusinessException("EXPORT_LIMIT_EXCEEDED",
                    "Export exceeds maximum of %d rows (found %d). Narrow the date range or add filters."
                            .formatted(MAX_EXPORT_ROWS, total),
                    400);
        }

        String filename = "key49-export-%s.csv".formatted(dateTo);
        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

        StreamingOutput stream = output -> writeDocumentsCsv(output, schemaName, baseHql, params);

        return Response.ok(stream)
                .header("Content-Disposition", "attachment; filename=\"%s\"".formatted(filename))
                .header("X-Request-Id", requestId)
                .header("X-Export-Count", total)
                .type("text/csv; charset=UTF-8")
                .build();
    }

    private void writeDocumentsCsv(OutputStream output, String schemaName, String baseHql,
            Map<String, Object> params) {
        try (var writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            writer.write(CSV_HEADER);
            writer.newLine();

            String orderHql = baseHql + " ORDER BY d.issueDate, d.sequenceNumber";
            int offset = 0;
            boolean hasMore = true;

            while (hasMore) {
                final int currentOffset = offset;
                List<Document> batch = tcm.withTenantSession(schemaName, em -> {
                    var q = em.createQuery(orderHql, Document.class)
                            .setFirstResult(currentOffset)
                            .setMaxResults(BATCH_SIZE);
                    params.forEach(q::setParameter);
                    return q.getResultList();
                });

                for (var doc : batch) {
                    writer.write(toCsvRow(doc));
                    writer.newLine();
                }

                hasMore = batch.size() == BATCH_SIZE;
                offset += batch.size();
            }

            writer.flush();
        } catch (java.io.IOException e) {
            log.error("Error writing CSV export", e);
        }
    }

    static String toCsvRow(Document doc) {
        return String.join(",",
                escapeCsv(doc.accessKey),
                escapeCsv(doc.documentType),
                escapeCsv(doc.establishment),
                escapeCsv(doc.issuePoint),
                escapeCsv(doc.sequenceNumber),
                escapeCsv(doc.recipientId),
                escapeCsv(doc.recipientName),
                doc.totalAmount != null ? doc.totalAmount.toPlainString() : "",
                doc.status != null ? doc.status.name() : "",
                doc.issueDate != null ? doc.issueDate.toString() : "",
                doc.authorizationDate != null
                        ? DateTimeFormatter.ISO_INSTANT.format(doc.authorizationDate) : "");
    }

    static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
