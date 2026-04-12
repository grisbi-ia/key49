package auracore.key49.api.portal;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.core.service.AuditService;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.queue.producer.DocumentEventProducer;
import auracore.key49.storage.ObjectStorageService;
import io.quarkus.qute.Location;
import io.vertx.core.http.HttpServerRequest;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

/**
 * Controlador del portal web de consulta de documentos.
 *
 * <p>
 * Usa Qute para server-side rendering con Pico CSS + HTMX. Solo lectura — no
 * permite crear ni modificar documentos.</p>
 */
@Path("/portal")
public class PortalResource {

    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DISPLAY_DATETIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final int DEFAULT_PER_PAGE = 20;

    @Inject
    Logger log;

    @Inject
    @Location("portal/login")
    Template login;

    @Inject
    @Location("portal/dashboard")
    Template dashboard;

    @Inject
    @Location("portal/detail")
    Template detail;

    @Inject
    @Location("portal/metrics")
    Template metrics;

    @Inject
    PortalSessionService sessionService;

    @Inject
    AuditService auditService;

    @Inject
    TenantConnectionManager tcm;

    @Inject
    ObjectStorageService storageService;

    @Inject
    DocumentEventProducer eventProducer;

    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "key49.portal.secure-cookie", defaultValue = "false")
    boolean secureCookie;

    @Context
    ContainerRequestContext requestContext;

    // ── Login ──
    @GET
    @Path("/login")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance loginPage(@QueryParam("error") String error) {
        return login.data("error", error);
    }

    @POST
    @Path("/login")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response doLogin(@FormParam("api_key") String apiKey,
            @FormParam("email") String email,
            @FormParam("password") String password,
            @Context HttpServerRequest httpRequest) {

        String sessionId;
        if (apiKey != null && !apiKey.isBlank()) {
            sessionId = sessionService.login(apiKey);
        } else if (email != null && !email.isBlank() && password != null && !password.isBlank()) {
            sessionId = sessionService.loginWithPassword(email, password);
        } else {
            sessionId = null;
        }

        if (sessionId == null) {
            return Response.seeOther(URI.create("/portal/login?error=invalid"))
                    .build();
        }

        var session = sessionService.validate(sessionId);
        if (session != null) {
            auditService.record(session.tenantId(), "portal", "portal.login",
                    "session", session.tenantId(),
                    AuditService.resolveIp(httpRequest), null);
        }

        return Response.seeOther(URI.create("/portal/"))
                .cookie(new NewCookie.Builder(PortalAuthFilter.SESSION_COOKIE)
                        .value(sessionId)
                        .path("/portal")
                        .httpOnly(true)
                        .secure(secureCookie)
                        .sameSite(NewCookie.SameSite.STRICT)
                        .build())
                .build();
    }

    // ── Logout ──
    @GET
    @Path("/logout")
    public Response logout(@Context HttpServerRequest httpRequest) {
        var cookies = requestContext.getCookies();
        var sessionCookie = cookies.get(PortalAuthFilter.SESSION_COOKIE);
        var sessionId = sessionCookie != null ? sessionCookie.getValue() : null;

        if (sessionId != null) {
            var session = sessionService.validate(sessionId);
            if (session != null) {
                auditService.record(session.tenantId(), "portal", "portal.logout",
                        "session", session.tenantId(),
                        AuditService.resolveIp(httpRequest), null);
            }
        }

        sessionService.logout(sessionId);
        return Response.seeOther(URI.create("/portal/login"))
                .cookie(new NewCookie.Builder(PortalAuthFilter.SESSION_COOKIE)
                        .value("")
                        .path("/portal")
                        .maxAge(0)
                        .httpOnly(true)
                        .secure(secureCookie)
                        .build())
                .build();
    }

    // ── Dashboard ──
    @GET
    @Path("/")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance dashboardPage(
            @QueryParam("status") String status,
            @QueryParam("doc_type") String docType,
            @QueryParam("date_from") String dateFromStr,
            @QueryParam("date_to") String dateToStr,
            @QueryParam("q") String searchQuery,
            @QueryParam("page") @DefaultValue("1") int page) {

        var session = getSession();
        var perPage = DEFAULT_PER_PAGE;

        return tcm.withTenantSession(session.schemaName(), em -> {
            var hql = new StringBuilder("FROM Document d WHERE 1=1");
            var countHql = new StringBuilder("SELECT count(d) FROM Document d WHERE 1=1");

            var conditions = buildFilterConditions(status, docType, dateFromStr, dateToStr, searchQuery);
            hql.append(conditions);
            countHql.append(conditions);
            hql.append(" ORDER BY d.issueDate DESC, d.createdAt DESC");

            var query = em.createQuery(hql.toString(), Document.class)
                    .setFirstResult((page - 1) * perPage)
                    .setMaxResults(perPage);

            var countQuery = em.createQuery(countHql.toString(), Long.class);

            applyFilterParameters(query, status, docType, dateFromStr, dateToStr, searchQuery);
            applyFilterParameters(countQuery, status, docType, dateFromStr, dateToStr, searchQuery);

            var total = countQuery.getSingleResult();
            var docs = query.getResultList();

            return buildDashboard(session, docs, total, page, perPage,
                    status, docType, dateFromStr, dateToStr, searchQuery);
        });
    }

    // ── Document detail ──
    @GET
    @Path("/documents/{id}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance documentDetail(@PathParam("id") UUID id,
            @QueryParam("retry") String retryResult) {
        var session = getSession();

        var doc = tcm.withTenantSession(session.schemaName(), em
                -> em.find(Document.class, id)
        );

        if (doc == null) {
            return detail.data("session", session)
                    .data("doc", null)
                    .data("error", "Documento no encontrado")
                    .data("retryResult", null)
                    .data("todayEc", null);
        }
        return detail.data("session", session)
                .data("doc", doc)
                .data("error", null)
                .data("retryResult", retryResult)
                .data("docTypeLabel", documentTypeLabel(doc.documentType))
                .data("statusLabel", statusLabel(doc.status))
                .data("statusClass", statusClass(doc.status))
                .data("timeline", buildTimeline(doc))
                .data("formatDate", DISPLAY_DATE)
                .data("formatDateTime", DISPLAY_DATETIME)
                .data("ecZone", Key49Constants.EC_ZONE)
                .data("todayEc", LocalDate.now(Key49Constants.EC_ZONE));
    }

    // ── Retry manual ──
    @POST
    @Path("/documents/{id}/retry")
    public Response retryDocument(@PathParam("id") UUID id,
            @Context HttpServerRequest httpRequest) {
        var session = getSession();
        var detailUri = URI.create("/portal/documents/" + id);

        var doc = tcm.withTenantSession(session.schemaName(), em
                -> em.find(Document.class, id)
        );

        if (doc == null) {
            return Response.seeOther(detailUri).build();
        }

        if (doc.status != DocumentStatus.FAILED) {
            log.warnf("Retry rejected: document %s is in status %s, expected FAILED", id, doc.status);
            return Response.seeOther(URI.create(detailUri + "?retry=invalid_status")).build();
        }

        var today = LocalDate.now(Key49Constants.EC_ZONE);
        if (!doc.issueDate.equals(today)) {
            log.warnf("Retry rejected: document %s issue_date %s != today %s", id, doc.issueDate, today);
            return Response.seeOther(URI.create(detailUri + "?retry=invalid_date")).build();
        }

        tcm.withTenantTransaction(session.schemaName(), em -> {
            var managed = em.find(Document.class, id);
            managed.transitionTo(DocumentStatus.CREATED);
            managed.retryCount = 0;
            managed.nextRetryAt = null;
            managed.updatedAt = Instant.now();
            return null;
        });

        eventProducer.sendToSign(DocumentEvent.of(id, session.schemaName(), "SIGN"));

        auditService.record(session.tenantId(), "portal", "portal.retry",
                "document", id, AuditService.resolveIp(httpRequest), null);

        log.infof("Manual retry initiated: documentId=%s, tenant=%s", id, session.schemaName());

        return Response.seeOther(URI.create(detailUri + "?retry=ok")).build();
    }

    // ── HTMX partial: status badge refresh ──
    @GET
    @Path("/documents/{id}/status")
    @Produces(MediaType.TEXT_HTML)
    public String documentStatusBadge(@PathParam("id") UUID id) {
        var session = getSession();

        var doc = tcm.withTenantSession(session.schemaName(), em
                -> em.find(Document.class, id)
        );

        if (doc == null) {
            return "<span>—</span>";
        }
        var label = statusLabel(doc.status);
        var cls = statusClass(doc.status);
        return "<mark class=\"%s\">%s</mark>".formatted(cls, label);
    }

    // ── Document downloads ──
    @GET
    @Path("/documents/{id}/xml")
    @Produces(MediaType.APPLICATION_XML)
    public Response downloadXml(@PathParam("id") UUID id) {
        var session = getSession();
        var doc = tcm.withTenantSession(session.schemaName(), em -> em.find(Document.class, id));
        if (doc == null || doc.authorizedXmlPath == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("XML autorizado no disponible")
                    .build();
        }
        byte[] bytes = storageService.retrieve(doc.authorizedXmlPath);
        return Response.ok(bytes)
                .type(MediaType.APPLICATION_XML)
                .header("Content-Disposition",
                        "attachment; filename=\"%s.xml\"".formatted(
                                doc.accessKey != null ? doc.accessKey : doc.id))
                .build();
    }

    @GET
    @Path("/documents/{id}/ride")
    @Produces("application/pdf")
    public Response downloadRide(@PathParam("id") UUID id) {
        var session = getSession();
        var doc = tcm.withTenantSession(session.schemaName(), em -> em.find(Document.class, id));
        if (doc == null || doc.ridePath == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("RIDE no disponible")
                    .build();
        }
        byte[] bytes = storageService.retrieve(doc.ridePath);
        return Response.ok(bytes)
                .type("application/pdf")
                .header("Content-Disposition",
                        "attachment; filename=\"%s.pdf\"".formatted(
                                doc.accessKey != null ? doc.accessKey : doc.id))
                .build();
    }

    // ── Metrics dashboard ──
    @GET
    @Path("/metrics")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance metricsPage() {
        var session = getSession();

        // 1. Document counts by status group
        var statusCounts = tcm.withTenantSession(session.schemaName(), em -> {
            @SuppressWarnings("unchecked")
            var rows = (List<Object[]>) em.createNativeQuery(
                    "SELECT status, count(*) FROM documents GROUP BY status").getResultList();
            long authorized = 0, inProcess = 0, failed = 0, total = 0;
            for (var row : rows) {
                var status = (String) row[0];
                var count = ((Number) row[1]).longValue();
                total += count;
                switch (status) {
                    case "AUTHORIZED", "NOTIFIED" ->
                        authorized += count;
                    case "REJECTED", "FAILED" ->
                        failed += count;
                    default ->
                        inProcess += count;
                }
            }
            return Map.of("authorized", authorized, "inProcess", inProcess,
                    "failed", failed, "total", total);
        });

        // 2. Documents per day (last 30 days)
        var dailyData = tcm.withTenantSession(session.schemaName(), em -> {
            @SuppressWarnings("unchecked")
            var rows = (List<Object[]>) em.createNativeQuery("""
                    SELECT issue_date, count(*) FROM documents
                    WHERE issue_date >= (current_date - interval '29 days')
                    GROUP BY issue_date ORDER BY issue_date""").getResultList();
            var entries = new ArrayList<DailyCount>();
            long maxCount = 1;
            for (var row : rows) {
                var date = row[0] instanceof java.sql.Date sd ? sd.toLocalDate() : (LocalDate) row[0];
                var count = ((Number) row[1]).longValue();
                entries.add(new DailyCount(date, count, 0));
                if (count > maxCount) {
                    maxCount = count;
                }
            }
            for (int i = 0; i < entries.size(); i++) {
                var e = entries.get(i);
                entries.set(i, new DailyCount(e.date, e.count,
                        (int) (e.count * 100 / maxCount)));
            }
            return entries;
        });

        // 3. Last document
        var lastDoc = tcm.withTenantSession(session.schemaName(), em -> {
            var list = em.createQuery(
                    "FROM Document d ORDER BY d.createdAt DESC", Document.class)
                    .setMaxResults(1)
                    .getResultList();
            return list.isEmpty() ? null : list.getFirst();
        });

        // 4. Certificate status
        var tenant = entityManager.find(Tenant.class, session.tenantId());
        String certStatus;
        long certDaysLeft = -1;
        if (tenant == null || tenant.certificateExpiration == null) {
            certStatus = "none";
        } else {
            certDaysLeft = Duration.between(Instant.now(), tenant.certificateExpiration).toDays();
            if (certDaysLeft < 0) {
                certStatus = "expired";
            } else if (certDaysLeft <= 30) {
                certStatus = "expiring";
            } else {
                certStatus = "valid";
            }
        }

        return metrics.data("session", session)
                .data("authorized", statusCounts.get("authorized"))
                .data("inProcess", statusCounts.get("inProcess"))
                .data("failed", statusCounts.get("failed"))
                .data("total", statusCounts.get("total"))
                .data("dailyData", dailyData)
                .data("lastDoc", lastDoc)
                .data("lastDocStatusLabel", lastDoc != null ? statusLabel(lastDoc.status) : null)
                .data("lastDocStatusClass", lastDoc != null ? statusClass(lastDoc.status) : null)
                .data("lastDocTypeLabel", lastDoc != null ? documentTypeLabel(lastDoc.documentType) : null)
                .data("certStatus", certStatus)
                .data("certDaysLeft", certDaysLeft)
                .data("certExpiration", tenant != null ? tenant.certificateExpiration : null)
                .data("formatDate", DISPLAY_DATE)
                .data("formatDateTime", DISPLAY_DATETIME)
                .data("ecZone", Key49Constants.EC_ZONE);
    }

    record DailyCount(LocalDate date, long count, int pct) {

    }

    // ── Helpers ──
    private PortalSessionService.PortalSession getSession() {
        return (PortalSessionService.PortalSession) requestContext.getProperty(PortalAuthFilter.PORTAL_SESSION_ATTR);
    }

    private String buildFilterConditions(String status, String docType, String dateFrom, String dateTo, String q) {
        var sb = new StringBuilder();
        if (status != null && !status.isBlank()) {
            sb.append(" AND d.status = :status");
        }
        if (docType != null && !docType.isBlank()) {
            sb.append(" AND d.documentType = :docType");
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            sb.append(" AND d.issueDate >= :dateFrom");
        }
        if (dateTo != null && !dateTo.isBlank()) {
            sb.append(" AND d.issueDate <= :dateTo");
        }
        if (q != null && !q.isBlank()) {
            sb.append(" AND (d.recipientName LIKE :q OR d.recipientId LIKE :qExact OR d.accessKey LIKE :qExact)");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void applyFilterParameters(Object query, String status, String docType, String dateFrom, String dateTo, String q) {
        var jpaQuery = (jakarta.persistence.Query) query;
        if (status != null && !status.isBlank()) {
            jpaQuery.setParameter("status", DocumentStatus.valueOf(status));
        }
        if (docType != null && !docType.isBlank()) {
            jpaQuery.setParameter("docType", docType);
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            jpaQuery.setParameter("dateFrom", LocalDate.parse(dateFrom));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            jpaQuery.setParameter("dateTo", LocalDate.parse(dateTo));
        }
        if (q != null && !q.isBlank()) {
            jpaQuery.setParameter("q", "%" + q + "%");
            jpaQuery.setParameter("qExact", q);
        }
    }

    private TemplateInstance buildDashboard(
            PortalSessionService.PortalSession session,
            List<Document> docs, long total, int page, int perPage,
            String status, String docType, String dateFrom, String dateTo, String q) {

        int totalPages = (int) Math.ceil((double) total / perPage);

        return dashboard.data("session", session)
                .data("docs", docs)
                .data("total", total)
                .data("page", page)
                .data("perPage", perPage)
                .data("totalPages", totalPages)
                .data("filterStatus", status)
                .data("filterDocType", docType)
                .data("filterDateFrom", dateFrom)
                .data("filterDateTo", dateTo)
                .data("filterQ", q)
                .data("statuses", DocumentStatus.values())
                .data("documentTypes", DocumentType.values())
                .data("docTypeLabels", docTypeLabelsMap())
                .data("formatDate", DISPLAY_DATE)
                .data("ecZone", Key49Constants.EC_ZONE);
    }

    record TimelineEntry(String label, String date, boolean completed, boolean active) {

    }

    private List<TimelineEntry> buildTimeline(Document doc) {
        var ecZone = Key49Constants.EC_ZONE;
        var fmt = DISPLAY_DATETIME;
        return List.of(
                entry("Creado", doc.createdAt, ecZone, fmt, true, doc.status == DocumentStatus.CREATED),
                entry("Firmado", doc.status.ordinal() >= DocumentStatus.SIGNED.ordinal() ? doc.updatedAt : null,
                        ecZone, fmt, doc.status.ordinal() >= DocumentStatus.SIGNED.ordinal(),
                        doc.status == DocumentStatus.SIGNED),
                entry("Enviado", doc.sriSubmissionDate, ecZone, fmt,
                        doc.status.ordinal() >= DocumentStatus.SENT.ordinal(),
                        doc.status == DocumentStatus.SENT),
                entry("Recibido", doc.status.ordinal() >= DocumentStatus.RECEIVED.ordinal() ? doc.sriSubmissionDate : null,
                        ecZone, fmt, doc.status.ordinal() >= DocumentStatus.RECEIVED.ordinal(),
                        doc.status == DocumentStatus.RECEIVED),
                entry("Autorizado", doc.authorizationDate, ecZone, fmt,
                        doc.status.ordinal() >= DocumentStatus.AUTHORIZED.ordinal(),
                        doc.status == DocumentStatus.AUTHORIZED),
                entry("Notificado", doc.status == DocumentStatus.NOTIFIED ? doc.emailSentAt : null,
                        ecZone, fmt, doc.status == DocumentStatus.NOTIFIED,
                        doc.status == DocumentStatus.NOTIFIED)
        );
    }

    private TimelineEntry entry(String label, java.time.Instant instant, ZoneId zone,
            DateTimeFormatter fmt, boolean completed, boolean active) {
        var dateStr = instant != null ? fmt.format(instant.atZone(zone)) : "—";
        return new TimelineEntry(label, dateStr, completed, active);
    }

    private static Map<String, String> docTypeLabelsMap() {
        return Arrays.stream(DocumentType.values())
                .collect(Collectors.toMap(DocumentType::sriCode, DocumentType::description));
    }

    static String documentTypeLabel(String code) {
        return switch (code) {
            case "01" ->
                "Factura";
            case "03" ->
                "Liquidación de Compra";
            case "04" ->
                "Nota de Crédito";
            case "05" ->
                "Nota de Débito";
            case "06" ->
                "Guía de Remisión";
            case "07" ->
                "Retención";
            default ->
                code;
        };
    }

    static String statusLabel(DocumentStatus s) {
        return switch (s) {
            case CREATED ->
                "Creado";
            case SIGNED ->
                "Firmado";
            case SENT ->
                "Enviado";
            case RECEIVED ->
                "Recibido";
            case AUTHORIZED ->
                "Autorizado";
            case NOTIFIED ->
                "Notificado";
            case REJECTED ->
                "Rechazado";
            case FAILED ->
                "Fallido";
            case RETRY ->
                "Reintentando";
            case VOIDED ->
                "Anulado";
        };
    }

    static String statusClass(DocumentStatus s) {
        return switch (s) {
            case AUTHORIZED, NOTIFIED ->
                "status-ok";
            case REJECTED, FAILED ->
                "status-error";
            case VOIDED ->
                "status-void";
            case RETRY ->
                "status-warn";
            default ->
                "status-pending";
        };
    }
}
