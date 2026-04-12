package auracore.key49.api.service;

import auracore.key49.api.dto.CreateWithholdingRequest;
import auracore.key49.api.dto.CreateWithholdingRequest.SupportingDocumentRequest;
import auracore.key49.api.dto.CreateWithholdingRequest.WithholdingLineRequest;
import auracore.key49.api.exception.BusinessException;
import auracore.key49.api.exception.BusinessException.FieldError;
import auracore.key49.api.exception.DuplicateDocumentException;
import auracore.key49.admin.metrics.DocumentMetrics;
import auracore.key49.core.Key49Constants;
import auracore.key49.core.service.QuotaService;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.core.model.enums.IdentificationType;
import auracore.key49.core.model.enums.PaymentMethod;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.core.tenant.TenantContext;
import auracore.key49.core.validation.SriValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Servicio de negocio para operaciones sobre comprobantes de retención
 * electrónicos.
 */
@ApplicationScoped
public class WithholdingService {

    private static final Pattern FISCAL_PERIOD_PATTERN = Pattern.compile("\\d{2}/\\d{4}");
    private static final Pattern DOC_NUMBER_PATTERN = Pattern.compile("\\d{3}-\\d{3}-\\d{9}|\\d{15}");

    @Inject
    Logger log;

    @Inject
    TenantContext tenantContext;

    @Inject
    TenantConnectionManager tcm;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    DocumentMetrics documentMetrics;

    @Inject
    QuotaService quotaService;

    // ── Crear comprobante de retención ──
    public Document createWithholding(CreateWithholdingRequest request,
            String idempotencyKey, String requestIp) {

        validateCreateRequest(request);

        final boolean[] created = {false};
        var doc = tcm.withTenantTransaction(tenantContext.getSchemaName(), em -> {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                Document existing = em.createQuery(
                        "FROM Document d WHERE d.idempotencyKey = :key AND d.documentType = :docType",
                        Document.class)
                        .setParameter("key", idempotencyKey)
                        .setParameter("docType", DocumentType.WITHHOLDING.sriCode())
                        .getResultStream().findFirst().orElse(null);
                if (existing != null) {
                    log.infof("Idempotent hit: returning existing withholding %s", existing.id);
                    return existing;
                }
            }
            created[0] = true;
            return checkUniquenessAndPersist(em, request, idempotencyKey, requestIp);
        });
        if (created[0]) {
            documentMetrics.recordCreated(tenantContext.getSchemaName(), doc.documentType);
        }
        return doc;
    }

    private Document checkUniquenessAndPersist(EntityManager em,
            CreateWithholdingRequest request, String idempotencyKey, String requestIp) {

        Document existing = em.createQuery(
                "FROM Document d WHERE d.documentType = :docType "
                + "AND d.establishment = :est AND d.issuePoint = :pt "
                + "AND d.sequenceNumber = :seq", Document.class)
                .setParameter("docType", DocumentType.WITHHOLDING.sriCode())
                .setParameter("est", request.establishment())
                .setParameter("pt", request.issuePoint())
                .setParameter("seq", request.sequenceNumber())
                .getResultStream().findFirst().orElse(null);

        if (existing == null) {
            quotaService.reserveQuota(em, tenantContext.getTenantId());
            return persistNewDocument(em, request, idempotencyKey, requestIp);
        }

        if (existing.status.isRetryableTerminal()) {
            quotaService.reserveQuota(em, tenantContext.getTenantId());
            return recycleDocument(em, existing, request, idempotencyKey, requestIp);
        }

        var docNumber = "%s-%s-%s".formatted(request.establishment(), request.issuePoint(), request.sequenceNumber());
        throw new DuplicateDocumentException(
                "DUPLICATE_DOCUMENT",
                "Withholding %s already exists with status %s".formatted(docNumber, existing.status.name()),
                existing.id, existing.status.name(), existing.accessKey, existing.authorizationDate);
    }

    private Document recycleDocument(EntityManager em, Document doc,
            CreateWithholdingRequest request, String idempotencyKey, String requestIp) {
        log.infof("Recycling failed document %s (was %s) for resubmission", doc.id, doc.status);

        doc.issueDate = request.issueDate();
        doc.recipientIdType = request.subject().idType();
        doc.recipientId = request.subject().id();
        doc.recipientName = request.subject().name();
        doc.recipientEmail = request.subject() != null ? request.subject().email() : null;
        doc.idempotencyKey = idempotencyKey;
        doc.requestIp = requestIp;

        try {
            doc.requestPayload = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new BusinessException(
                    "SERIALIZATION_ERROR", "Failed to serialize request", 500);
        }

        computeAndSetTotals(doc, request);

        doc.status = DocumentStatus.CREATED;
        doc.accessKey = null;
        doc.authorizationNumber = null;
        doc.authorizationDate = null;
        doc.sriSubmissionDate = null;
        doc.lastErrorCode = null;
        doc.lastErrorMessage = null;
        doc.sriMessages = null;
        doc.unsignedXmlPath = null;
        doc.signedXmlPath = null;
        doc.authorizedXmlPath = null;
        doc.ridePath = null;
        doc.retryCount = 0;
        doc.nextRetryAt = null;
        doc.updatedAt = Instant.now();

        em.merge(doc);
        var outbox = OutboxEvent.create(doc.id, "doc.sign", "{}");
        em.persist(outbox);
        em.flush();
        return doc;
    }

    private Document persistNewDocument(EntityManager em,
            CreateWithholdingRequest request, String idempotencyKey, String requestIp) {

        var doc = new Document();
        doc.documentType = DocumentType.WITHHOLDING.sriCode();
        doc.establishment = request.establishment();
        doc.issuePoint = request.issuePoint();
        doc.sequenceNumber = request.sequenceNumber();
        doc.issueDate = request.issueDate();
        doc.recipientIdType = request.subject().idType();
        doc.recipientId = request.subject().id();
        doc.recipientName = request.subject().name();
        doc.recipientEmail = request.subject() != null ? request.subject().email() : null;
        doc.idempotencyKey = idempotencyKey;
        doc.requestIp = requestIp;
        doc.requestOrigin = "JSON";

        try {
            doc.requestPayload = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new BusinessException(
                    "SERIALIZATION_ERROR", "Failed to serialize request", 500);
        }

        computeAndSetTotals(doc, request);

        doc.createdAt = Instant.now();
        doc.updatedAt = Instant.now();

        log.infof("Creating withholding %s-%s-%s for subject %s",
                doc.establishment, doc.issuePoint, doc.sequenceNumber, doc.recipientId);

        em.persist(doc);
        var outbox = OutboxEvent.create(doc.id, "doc.sign", "{}");
        em.persist(outbox);
        em.flush();
        return doc;
    }

    // ── Consultar por ID ──
    public Document findById(UUID id) {
        Document doc = tcm.withTenantSession(tenantContext.getSchemaName(), em
                -> em.find(Document.class, id));
        if (doc == null) {
            throw new BusinessException(
                    "DOCUMENT_NOT_FOUND", "Withholding not found: " + id, 404);
        }
        return doc;
    }

    // ── Listar comprobantes de retención ──
    public PagedResult listWithholdings(String status, LocalDate dateFrom,
            LocalDate dateTo, String recipientId, String accessKey,
            int page, int perPage, String sort) {

        int safePage = Math.max(1, page);
        int safePerPage = Math.max(1, Math.min(100, perPage));

        return tcm.withTenantSession(tenantContext.getSchemaName(), em -> {
            var hql = new StringBuilder("FROM Document d WHERE d.documentType = :docType");
            var params = new java.util.LinkedHashMap<String, Object>();
            params.put("docType", DocumentType.WITHHOLDING.sriCode());

            if (status != null && !status.isBlank()) {
                hql.append(" AND d.status = :status");
                params.put("status", DocumentStatus.valueOf(status));
            }
            if (dateFrom != null) {
                hql.append(" AND d.issueDate >= :dateFrom");
                params.put("dateFrom", dateFrom);
            }
            if (dateTo != null) {
                hql.append(" AND d.issueDate <= :dateTo");
                params.put("dateTo", dateTo);
            }
            if (recipientId != null && !recipientId.isBlank()) {
                hql.append(" AND d.recipientId = :recipientId");
                params.put("recipientId", recipientId);
            }
            if (accessKey != null && !accessKey.isBlank()) {
                hql.append(" AND d.accessKey = :accessKey");
                params.put("accessKey", accessKey);
            }

            String countHql = "SELECT count(d) " + hql;
            hql.append(resolveOrderBy(sort));

            var countQuery = em.createQuery(countHql, Long.class);
            var dataQuery = em.createQuery(hql.toString(), Document.class)
                    .setFirstResult((safePage - 1) * safePerPage)
                    .setMaxResults(safePerPage);

            params.forEach((k, v) -> {
                countQuery.setParameter(k, v);
                dataQuery.setParameter(k, v);
            });

            Long total = countQuery.getSingleResult();
            List<Document> docs = dataQuery.getResultList();
            return new PagedResult(docs, total);
        });
    }

    // ── Anular comprobante de retención localmente ──
    public Document voidWithholding(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(
                    "VALIDATION_ERROR", "Void reason is required", 400);
        }

        return tcm.withTenantTransaction(tenantContext.getSchemaName(), em -> {
            Document doc = em.find(Document.class, id);
            if (doc == null) {
                throw new BusinessException(
                        "DOCUMENT_NOT_FOUND", "Document not found: " + id, 404);
            }

            if (doc.status != DocumentStatus.AUTHORIZED
                    && doc.status != DocumentStatus.NOTIFIED) {
                throw new BusinessException(
                        "INVALID_STATE_TRANSITION",
                        "Cannot void document in state " + doc.status,
                        409);
            }

            doc.transitionTo(DocumentStatus.VOIDED);
            doc.voidedAt = Instant.now();
            doc.voidReason = reason;
            doc.updatedAt = Instant.now();

            return doc;
        });
    }

    // ── Reenviar email ──
    public Instant resendEmail(UUID id) {
        return tcm.withTenantTransaction(tenantContext.getSchemaName(), em -> {
            Document doc = em.find(Document.class, id);
            if (doc == null) {
                throw new BusinessException(
                        "DOCUMENT_NOT_FOUND", "Document not found: " + id, 404);
            }

            if (doc.status != DocumentStatus.AUTHORIZED
                    && doc.status != DocumentStatus.NOTIFIED) {
                throw new BusinessException(
                        "INVALID_STATE_TRANSITION",
                        "Cannot resend email for document in state " + doc.status,
                        409);
            }
            if (doc.recipientEmail == null || doc.recipientEmail.isBlank()) {
                throw new BusinessException(
                        "VALIDATION_ERROR",
                        "Document has no recipient email configured",
                        400);
            }

            var outbox = OutboxEvent.create(doc.id, "doc.notify", "{}");
            em.persist(outbox);
            return Instant.now();
        });
    }

    // ── Validaciones ──
    void validateCreateRequest(CreateWithholdingRequest request) {
        var errors = new ArrayList<FieldError>();

        if (!SriValidator.isValidEstablishment(request.establishment())) {
            errors.add(new FieldError("establishment", "Must be 3 digits", "INVALID_ESTABLISHMENT"));
        }
        if (!SriValidator.isValidIssuePoint(request.issuePoint())) {
            errors.add(new FieldError("issue_point", "Must be 3 digits", "INVALID_ISSUE_POINT"));
        }
        if (!SriValidator.isValidSequenceNumber(request.sequenceNumber())) {
            errors.add(new FieldError("sequence_number", "Must be 9 digits", "INVALID_SEQUENCE_NUMBER"));
        }

        LocalDate today = LocalDate.now(Key49Constants.EC_ZONE);
        if (request.issueDate() == null || !today.equals(request.issueDate())) {
            errors.add(new FieldError("issue_date",
                    "Must be today's date (%s)".formatted(today), "INVALID_ISSUE_DATE"));
        }

        validateSubject(request.subject(), errors);
        validateFiscalPeriod(request.fiscalPeriod(), errors);
        validateSupportingDocuments(request.supportingDocuments(), errors);

        if (!errors.isEmpty()) {
            throw new BusinessException("VALIDATION_ERROR", "Request contains invalid fields", 400, errors);
        }
    }

    private void validateSubject(CreateWithholdingRequest.SubjectRequest subject,
            List<FieldError> errors) {
        if (subject == null) {
            errors.add(new FieldError("subject", "Required", "REQUIRED"));
            return;
        }
        if (subject.idType() == null || subject.idType().isBlank()) {
            errors.add(new FieldError("subject.id_type", "Required", "REQUIRED"));
        } else {
            try {
                IdentificationType.fromSriCode(subject.idType());
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError("subject.id_type",
                        "Invalid identification type code", "INVALID_FORMAT"));
            }
        }
        if (!SriValidator.isValidIdentification(subject.idType(), subject.id())) {
            errors.add(new FieldError("subject.id",
                    "Invalid identification for type " + subject.idType(), "INVALID_SUBJECT_ID"));
        }
        if (subject.name() == null || subject.name().isBlank()) {
            errors.add(new FieldError("subject.name", "Required", "REQUIRED"));
        }
    }

    private void validateFiscalPeriod(String fiscalPeriod, List<FieldError> errors) {
        if (fiscalPeriod == null || fiscalPeriod.isBlank()) {
            errors.add(new FieldError("fiscal_period", "Required (MM/yyyy)", "REQUIRED"));
            return;
        }
        if (!FISCAL_PERIOD_PATTERN.matcher(fiscalPeriod).matches()) {
            errors.add(new FieldError("fiscal_period",
                    "Must match format MM/yyyy", "INVALID_FORMAT"));
            return;
        }
        int month = Integer.parseInt(fiscalPeriod.substring(0, 2));
        if (month < 1 || month > 12) {
            errors.add(new FieldError("fiscal_period",
                    "Month must be between 01 and 12", "INVALID_FORMAT"));
        }
    }

    private void validateSupportingDocuments(List<SupportingDocumentRequest> docs,
            List<FieldError> errors) {
        if (docs == null || docs.isEmpty()) {
            errors.add(new FieldError("supporting_documents",
                    "At least one supporting document is required", "REQUIRED"));
            return;
        }
        for (int i = 0; i < docs.size(); i++) {
            var sd = docs.get(i);
            String prefix = "supporting_documents[%d]".formatted(i);

            if (sd.supportCode() == null || sd.supportCode().isBlank()) {
                errors.add(new FieldError(prefix + ".support_code", "Required", "REQUIRED"));
            }
            if (sd.documentCode() == null || sd.documentCode().isBlank()) {
                errors.add(new FieldError(prefix + ".document_code", "Required", "REQUIRED"));
            }
            if (sd.documentNumber() == null || sd.documentNumber().isBlank()) {
                errors.add(new FieldError(prefix + ".document_number", "Required", "REQUIRED"));
            } else if (!DOC_NUMBER_PATTERN.matcher(sd.documentNumber()).matches()) {
                errors.add(new FieldError(prefix + ".document_number",
                        "Must match format 000-000-000000000", "INVALID_FORMAT"));
            }
            if (sd.issueDate() == null) {
                errors.add(new FieldError(prefix + ".issue_date", "Required", "REQUIRED"));
            }
            if (sd.paymentLocality() == null || sd.paymentLocality().isBlank()) {
                errors.add(new FieldError(prefix + ".payment_locality", "Required", "REQUIRED"));
            } else if (!"01".equals(sd.paymentLocality()) && !"02".equals(sd.paymentLocality())) {
                errors.add(new FieldError(prefix + ".payment_locality",
                        "Must be 01 (local) or 02 (foreign)", "INVALID_FORMAT"));
            }
            if (sd.totalWithoutTax() == null) {
                errors.add(new FieldError(prefix + ".total_without_tax", "Required", "REQUIRED"));
            }
            if (sd.totalAmount() == null) {
                errors.add(new FieldError(prefix + ".total_amount", "Required", "REQUIRED"));
            }

            validateWithholdingLines(sd.withholdings(), prefix, errors);
            validatePayments(sd.payments(), prefix, errors);
        }
    }

    private void validateWithholdingLines(List<WithholdingLineRequest> lines,
            String prefix, List<FieldError> errors) {
        if (lines == null || lines.isEmpty()) {
            errors.add(new FieldError(prefix + ".withholdings",
                    "At least one withholding line is required", "REQUIRED"));
            return;
        }
        for (int j = 0; j < lines.size(); j++) {
            var wh = lines.get(j);
            String wp = prefix + ".withholdings[%d]".formatted(j);

            if (wh.code() == null || wh.code().isBlank()) {
                errors.add(new FieldError(wp + ".code", "Required", "REQUIRED"));
            } else if (!"1".equals(wh.code()) && !"2".equals(wh.code()) && !"6".equals(wh.code())) {
                errors.add(new FieldError(wp + ".code",
                        "Must be 1 (Renta), 2 (IVA) or 6 (ISD)", "INVALID_FORMAT"));
            }
            if (wh.retentionCode() == null || wh.retentionCode().isBlank()) {
                errors.add(new FieldError(wp + ".retention_code", "Required", "REQUIRED"));
            }
            if (wh.taxableBase() == null || wh.taxableBase().compareTo(BigDecimal.ZERO) < 0) {
                errors.add(new FieldError(wp + ".taxable_base",
                        "Must be zero or positive", "INVALID_FORMAT"));
            }
            if (wh.retentionRate() == null || wh.retentionRate().compareTo(BigDecimal.ZERO) < 0) {
                errors.add(new FieldError(wp + ".retention_rate",
                        "Must be zero or positive", "INVALID_FORMAT"));
            }
            if (wh.retainedAmount() == null || wh.retainedAmount().compareTo(BigDecimal.ZERO) < 0) {
                errors.add(new FieldError(wp + ".retained_amount",
                        "Must be zero or positive", "INVALID_FORMAT"));
            }
        }
    }

    private void validatePayments(List<CreateWithholdingRequest.PaymentRequest> payments,
            String prefix, List<FieldError> errors) {
        if (payments == null || payments.isEmpty()) {
            return;
        }
        for (int j = 0; j < payments.size(); j++) {
            var pay = payments.get(j);
            String pp = prefix + ".payments[%d]".formatted(j);

            if (pay.paymentMethod() == null || pay.paymentMethod().isBlank()) {
                errors.add(new FieldError(pp + ".payment_method", "Required", "REQUIRED"));
            } else {
                try {
                    PaymentMethod.fromSriCode(pay.paymentMethod());
                } catch (IllegalArgumentException e) {
                    errors.add(new FieldError(pp + ".payment_method",
                            "Invalid payment method code", "INVALID_FORMAT"));
                }
            }
            if (pay.total() == null || pay.total().compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(new FieldError(pp + ".total", "Must be positive", "INVALID_FORMAT"));
            }
        }
    }

    // ── Cálculo de totales ──
    void computeAndSetTotals(Document doc, CreateWithholdingRequest request) {
        BigDecimal totalRetained = BigDecimal.ZERO;
        BigDecimal ivaRetained = BigDecimal.ZERO;
        BigDecimal subtotalBeforeTax = BigDecimal.ZERO;

        if (request.supportingDocuments() != null) {
            for (var sd : request.supportingDocuments()) {
                if (sd.totalWithoutTax() != null) {
                    subtotalBeforeTax = subtotalBeforeTax.add(sd.totalWithoutTax());
                }
                if (sd.withholdings() != null) {
                    for (var wh : sd.withholdings()) {
                        if (wh.retainedAmount() != null) {
                            totalRetained = totalRetained.add(wh.retainedAmount());
                            if ("2".equals(wh.code())) {
                                ivaRetained = ivaRetained.add(wh.retainedAmount());
                            }
                        }
                    }
                }
            }
        }

        doc.subtotalBeforeTax = subtotalBeforeTax.setScale(2, RoundingMode.HALF_UP);
        doc.totalDiscount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        doc.vatAmount = ivaRetained.setScale(2, RoundingMode.HALF_UP);
        doc.iceAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        doc.totalAmount = totalRetained.setScale(2, RoundingMode.HALF_UP);
    }

    // ── Utilidades ──
    private String resolveOrderBy(String sort) {
        if (sort == null || sort.isBlank()) {
            sort = "-issue_date";
        }
        boolean desc = sort.startsWith("-");
        String field = desc ? sort.substring(1) : sort;
        String column = switch (field) {
            case "issue_date" ->
                "d.issueDate";
            case "created_at" ->
                "d.createdAt";
            case "total_amount" ->
                "d.totalAmount";
            case "status" ->
                "d.status";
            default ->
                "d.issueDate";
        };
        return " ORDER BY " + column + (desc ? " DESC" : " ASC");
    }

    public record PagedResult(List<Document> items, long total) {

    }
}
