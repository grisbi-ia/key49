package auracore.key49.api.portal;

import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.core.tenant.TenantConnectionManager;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.*;
import org.jboss.logging.Logger;

import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

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
    PortalSessionService sessionService;

    @Inject
    TenantConnectionManager tcm;

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
    public Uni<Response> doLogin(@FormParam("api_key") String apiKey) {
        return sessionService.login(apiKey)
                .map(sessionId -> {
                    if (sessionId == null) {
                        return Response.seeOther(URI.create("/portal/login?error=invalid"))
                                .build();
                    }
                    return Response.seeOther(URI.create("/portal/"))
                            .cookie(new NewCookie.Builder(PortalAuthFilter.SESSION_COOKIE)
                                    .value(sessionId)
                                    .path("/portal")
                                    .httpOnly(true)
                                    .sameSite(NewCookie.SameSite.STRICT)
                                    .build())
                            .build();
                });
    }

    // ── Logout ──
    @GET
    @Path("/logout")
    public Uni<Response> logout() {
        var cookies = requestContext.getCookies();
        var sessionCookie = cookies.get(PortalAuthFilter.SESSION_COOKIE);
        var sessionId = sessionCookie != null ? sessionCookie.getValue() : null;

        return sessionService.logout(sessionId)
                .replaceWith(Response.seeOther(URI.create("/portal/login"))
                        .cookie(new NewCookie.Builder(PortalAuthFilter.SESSION_COOKIE)
                                .value("")
                                .path("/portal")
                                .maxAge(0)
                                .httpOnly(true)
                                .build())
                        .build());
    }

    // ── Dashboard ──
    @GET
    @Path("/")
    @Produces(MediaType.TEXT_HTML)
    public Uni<TemplateInstance> dashboardPage(
            @QueryParam("status") String status,
            @QueryParam("doc_type") String docType,
            @QueryParam("date_from") String dateFromStr,
            @QueryParam("date_to") String dateToStr,
            @QueryParam("q") String searchQuery,
            @QueryParam("page") @DefaultValue("1") int page) {

        var session = getSession();
        var perPage = DEFAULT_PER_PAGE;

        return tcm.withTenantSession(session.schemaName(), s -> {
            var hql = new StringBuilder("FROM Document d WHERE 1=1");
            var countHql = new StringBuilder("SELECT count(d) FROM Document d WHERE 1=1");

            // Build filter conditions
            var conditions = buildFilterConditions(status, docType, dateFromStr, dateToStr, searchQuery);
            hql.append(conditions);
            countHql.append(conditions);
            hql.append(" ORDER BY d.issueDate DESC, d.createdAt DESC");

            var query = s.createQuery(hql.toString(), Document.class)
                    .setFirstResult((page - 1) * perPage)
                    .setMaxResults(perPage);

            var countQuery = s.createQuery(countHql.toString(), Long.class);

            applyFilterParameters(query, status, docType, dateFromStr, dateToStr, searchQuery);
            applyFilterParameters(countQuery, status, docType, dateFromStr, dateToStr, searchQuery);

            return countQuery.getSingleResult()
                    .chain(total -> query.getResultList()
                    .map(docs -> buildDashboard(session, docs, total, page, perPage,
                    status, docType, dateFromStr, dateToStr, searchQuery)));
        });
    }

    // ── Document detail ──
    @GET
    @Path("/documents/{id}")
    @Produces(MediaType.TEXT_HTML)
    public Uni<TemplateInstance> documentDetail(@PathParam("id") UUID id) {
        var session = getSession();

        return tcm.withTenantSession(session.schemaName(), s
                -> s.find(Document.class, id)
        ).map(doc -> {
            if (doc == null) {
                return detail.data("session", session)
                        .data("doc", null)
                        .data("error", "Documento no encontrado");
            }
            return detail.data("session", session)
                    .data("doc", doc)
                    .data("error", null)
                    .data("docTypeLabel", documentTypeLabel(doc.documentType))
                    .data("statusLabel", statusLabel(doc.status))
                    .data("statusClass", statusClass(doc.status))
                    .data("timeline", buildTimeline(doc))
                    .data("formatDate", DISPLAY_DATE)
                    .data("formatDateTime", DISPLAY_DATETIME)
                    .data("ecZone", Key49Constants.EC_ZONE);
        });
    }

    // ── HTMX partial: status badge refresh ──
    @GET
    @Path("/documents/{id}/status")
    @Produces(MediaType.TEXT_HTML)
    public Uni<String> documentStatusBadge(@PathParam("id") UUID id) {
        var session = getSession();

        return tcm.withTenantSession(session.schemaName(), s
                -> s.find(Document.class, id)
        ).map(doc -> {
            if (doc == null) {
                return "<span>—</span>";
            }
            var label = statusLabel(doc.status);
            var cls = statusClass(doc.status);
            return "<mark class=\"%s\">%s</mark>".formatted(cls, label);
        });
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
        var mq = (org.hibernate.reactive.mutiny.Mutiny.SelectionQuery<?>) query;
        if (status != null && !status.isBlank()) {
            mq.setParameter("status", DocumentStatus.valueOf(status));
        }
        if (docType != null && !docType.isBlank()) {
            mq.setParameter("docType", docType);
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            mq.setParameter("dateFrom", LocalDate.parse(dateFrom));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            mq.setParameter("dateTo", LocalDate.parse(dateTo));
        }
        if (q != null && !q.isBlank()) {
            mq.setParameter("q", "%" + q + "%");
            mq.setParameter("qExact", q);
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
