package auracore.key49.api.service;

import auracore.key49.api.dto.CreateCreditNoteRequest;
import auracore.key49.api.dto.CreateCreditNoteRequest.ItemRequest;
import auracore.key49.api.dto.CreateCreditNoteRequest.TaxRequest;
import auracore.key49.api.exception.BusinessException;
import auracore.key49.api.exception.BusinessException.FieldError;
import auracore.key49.api.exception.DuplicateDocumentException;
import auracore.key49.admin.metrics.DocumentMetrics;
import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.core.model.enums.IdentificationType;
import auracore.key49.core.model.enums.TaxType;
import auracore.key49.core.model.enums.VatRate;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Servicio de negocio para operaciones sobre notas de crédito electrónicas.
 */
@ApplicationScoped
public class CreditNoteService {

    private static final Pattern MODIFIED_DOC_NUMBER_PATTERN = Pattern.compile("\\d{3}-\\d{3}-\\d{9}");

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

    // ── Crear nota de crédito ──
    public Document createCreditNote(CreateCreditNoteRequest request, String idempotencyKey, String requestIp) {
        validateCreateRequest(request);

        final boolean[] created = {false};
        var doc = tcm.withTenantTransaction(tenantContext.getSchemaName(), em -> {
            if (idempotencyKey != null) {
                Document existing = em.createQuery(
                        "FROM Document d WHERE d.idempotencyKey = :key", Document.class)
                        .setParameter("key", idempotencyKey)
                        .getResultStream().findFirst().orElse(null);
                if (existing != null) {
                    log.infof("Idempotent request found | docId=%s key=%s", existing.id, idempotencyKey);
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

    private Document checkUniquenessAndPersist(EntityManager em, CreateCreditNoteRequest request,
            String idempotencyKey, String requestIp) {
        Document existing = em.createQuery(
                "FROM Document d WHERE d.documentType = :dt AND d.establishment = :est "
                + "AND d.issuePoint = :ip AND d.sequenceNumber = :sn", Document.class)
                .setParameter("dt", DocumentType.CREDIT_NOTE.sriCode())
                .setParameter("est", request.establishment())
                .setParameter("ip", request.issuePoint())
                .setParameter("sn", request.sequenceNumber())
                .getResultStream().findFirst().orElse(null);

        if (existing == null) {
            return persistNewDocument(em, request, idempotencyKey, requestIp);
        }

        if (existing.status.isRetryableTerminal()) {
            return recycleDocument(em, existing, request, idempotencyKey, requestIp);
        }

        var docNumber = "%s-%s-%s".formatted(request.establishment(), request.issuePoint(), request.sequenceNumber());
        throw new DuplicateDocumentException(
                "DUPLICATE_DOCUMENT",
                "Credit note %s already exists with status %s".formatted(docNumber, existing.status.name()),
                existing.id, existing.status.name(), existing.accessKey, existing.authorizationDate);
    }

    private Document recycleDocument(EntityManager em, Document doc, CreateCreditNoteRequest request,
            String idempotencyKey, String requestIp) {
        log.infof("Recycling failed document %s (was %s) for resubmission", doc.id, doc.status);

        doc.issueDate = request.issueDate();
        doc.recipientIdType = request.recipient().idType();
        doc.recipientId = request.recipient().id();
        doc.recipientName = request.recipient().name();
        doc.recipientEmail = request.recipient().email();
        doc.recipientPhone = request.recipient().phone();
        doc.idempotencyKey = idempotencyKey;
        doc.requestIp = requestIp;

        computeAndSetTotals(doc, request.items());

        try {
            doc.requestPayload = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request payload", e);
        }

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

    private Document persistNewDocument(EntityManager em, CreateCreditNoteRequest request,
            String idempotencyKey, String requestIp) {
        var doc = new Document();
        doc.documentType = DocumentType.CREDIT_NOTE.sriCode();
        doc.establishment = request.establishment();
        doc.issuePoint = request.issuePoint();
        doc.sequenceNumber = request.sequenceNumber();
        doc.issueDate = request.issueDate();
        doc.recipientIdType = request.recipient().idType();
        doc.recipientId = request.recipient().id();
        doc.recipientName = request.recipient().name();
        doc.recipientEmail = request.recipient().email();
        doc.recipientPhone = request.recipient().phone();
        doc.requestOrigin = "JSON";
        doc.idempotencyKey = idempotencyKey;
        doc.requestIp = requestIp;
        doc.status = DocumentStatus.CREATED;
        doc.createdAt = Instant.now();
        doc.updatedAt = Instant.now();

        computeAndSetTotals(doc, request.items());

        try {
            doc.requestPayload = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request payload", e);
        }

        em.persist(doc);
        var outbox = OutboxEvent.create(doc.id, "doc.sign", "{}");
        em.persist(outbox);
        em.flush();
        return doc;
    }

    // ── Consultar nota de crédito por ID ──
    public Document findById(UUID id) {
        Document doc = tcm.withTenantSession(tenantContext.getSchemaName(), em
                -> em.find(Document.class, id));
        if (doc == null) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "Document not found: " + id, 404);
        }
        return doc;
    }

    // ── Listar notas de crédito con filtros y paginación ──
    public PagedResult listCreditNotes(String status, LocalDate dateFrom, LocalDate dateTo,
            String recipientId, String accessKey,
            int page, int perPage, String sort) {
        int safePage = Math.max(1, page);
        int safePerPage = Math.max(1, Math.min(100, perPage));

        return tcm.withTenantSession(tenantContext.getSchemaName(), em -> {
            var hql = new StringBuilder("FROM Document d WHERE d.documentType = :dt");
            Map<String, Object> params = new HashMap<>();
            params.put("dt", DocumentType.CREDIT_NOTE.sriCode());

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

    // ── Anular nota de crédito localmente ──
    public Document voidCreditNote(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("VALIDATION_ERROR", "Void reason is required", 400);
        }

        return tcm.withTenantTransaction(tenantContext.getSchemaName(), em -> {
            Document doc = em.find(Document.class, id);
            if (doc == null) {
                throw new BusinessException("DOCUMENT_NOT_FOUND", "Document not found: " + id, 404);
            }

            if (doc.status != DocumentStatus.AUTHORIZED && doc.status != DocumentStatus.NOTIFIED) {
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
                throw new BusinessException("DOCUMENT_NOT_FOUND", "Document not found: " + id, 404);
            }

            if (doc.status != DocumentStatus.AUTHORIZED && doc.status != DocumentStatus.NOTIFIED) {
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
    void validateCreateRequest(CreateCreditNoteRequest request) {
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

        validateRecipient(request.recipient(), errors);
        validateModifiedDocument(request, errors);
        validateItems(request.items(), errors);

        if (!errors.isEmpty()) {
            throw new BusinessException("VALIDATION_ERROR", "Request contains invalid fields", 400, errors);
        }
    }

    private void validateRecipient(CreateCreditNoteRequest.RecipientRequest recipient, List<FieldError> errors) {
        if (recipient == null) {
            errors.add(new FieldError("recipient", "Required", "REQUIRED"));
            return;
        }
        if (recipient.idType() == null || recipient.idType().isBlank()) {
            errors.add(new FieldError("recipient.id_type", "Required", "REQUIRED"));
        } else {
            try {
                IdentificationType.fromSriCode(recipient.idType());
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError("recipient.id_type", "Invalid identification type code", "INVALID_FORMAT"));
            }
        }
        if (!SriValidator.isValidIdentification(recipient.idType(), recipient.id())) {
            errors.add(new FieldError("recipient.id",
                    "Invalid identification for type " + recipient.idType(), "INVALID_RECIPIENT_ID"));
        }
        if (recipient.name() == null || recipient.name().isBlank()) {
            errors.add(new FieldError("recipient.name", "Required", "REQUIRED"));
        }
    }

    private void validateModifiedDocument(CreateCreditNoteRequest request, List<FieldError> errors) {
        if (request.modifiedDocumentCode() == null || request.modifiedDocumentCode().isBlank()) {
            errors.add(new FieldError("modified_document_code", "Required", "REQUIRED"));
        } else {
            try {
                DocumentType.fromSriCode(request.modifiedDocumentCode());
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError("modified_document_code",
                        "Invalid document type code", "INVALID_FORMAT"));
            }
        }
        if (request.modifiedDocumentNumber() == null || request.modifiedDocumentNumber().isBlank()) {
            errors.add(new FieldError("modified_document_number", "Required", "REQUIRED"));
        } else if (!MODIFIED_DOC_NUMBER_PATTERN.matcher(request.modifiedDocumentNumber()).matches()) {
            errors.add(new FieldError("modified_document_number",
                    "Must match format 001-001-000000001", "INVALID_FORMAT"));
        }
        if (request.modifiedDocumentDate() == null) {
            errors.add(new FieldError("modified_document_date", "Required", "REQUIRED"));
        }
        if (request.reason() == null || request.reason().isBlank()) {
            errors.add(new FieldError("reason", "Required", "REQUIRED"));
        }
    }

    private void validateItems(List<ItemRequest> items, List<FieldError> errors) {
        if (items == null || items.isEmpty()) {
            errors.add(new FieldError("items", "At least one item is required", "REQUIRED"));
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            var item = items.get(i);
            if (item.description() == null || item.description().isBlank()) {
                errors.add(new FieldError("items[%d].description".formatted(i), "Required", "REQUIRED"));
            }
            if (item.quantity() == null || item.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(new FieldError("items[%d].quantity".formatted(i), "Must be positive", "INVALID_FORMAT"));
            }
            if (item.unitPrice() == null || item.unitPrice().compareTo(BigDecimal.ZERO) < 0) {
                errors.add(new FieldError("items[%d].unit_price".formatted(i), "Must be non-negative", "INVALID_FORMAT"));
            }
            if (item.taxes() != null) {
                for (int j = 0; j < item.taxes().size(); j++) {
                    validateTax(item.taxes().get(j), i, j, errors);
                }
            }
        }
    }

    private void validateTax(TaxRequest tax, int itemIdx, int taxIdx, List<FieldError> errors) {
        String prefix = "items[%d].taxes[%d]".formatted(itemIdx, taxIdx);
        if (tax.code() == null) {
            errors.add(new FieldError(prefix + ".code", "Required", "REQUIRED"));
            return;
        }
        try {
            TaxType.fromSriCode(tax.code());
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError(prefix + ".code", "Invalid tax type code", "INVALID_FORMAT"));
        }
        if ("2".equals(tax.code()) && tax.rateCode() != null) {
            try {
                VatRate.fromSriCode(tax.rateCode());
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError(prefix + ".rate_code", "Invalid VAT rate code", "INVALID_FORMAT"));
            }
        }
    }

    // ── Cálculo de totales ──
    void computeAndSetTotals(Document doc, List<ItemRequest> items) {
        BigDecimal subtotalBeforeTax = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal subtotalVat0 = BigDecimal.ZERO;
        BigDecimal subtotalVat12 = BigDecimal.ZERO;
        BigDecimal subtotalVat15 = BigDecimal.ZERO;
        BigDecimal subtotalNonTaxable = BigDecimal.ZERO;
        BigDecimal subtotalExempt = BigDecimal.ZERO;
        BigDecimal vatAmount = BigDecimal.ZERO;
        BigDecimal iceAmount = BigDecimal.ZERO;

        for (var item : items) {
            BigDecimal lineTotal = item.quantity().multiply(item.unitPrice());
            BigDecimal discount = item.discount() != null ? item.discount() : BigDecimal.ZERO;
            BigDecimal discountedTotal = lineTotal.subtract(discount);

            subtotalBeforeTax = subtotalBeforeTax.add(discountedTotal);
            totalDiscount = totalDiscount.add(discount);

            if (item.taxes() != null) {
                for (var tax : item.taxes()) {
                    if ("2".equals(tax.code())) {
                        BigDecimal taxBase = discountedTotal;
                        BigDecimal taxRate = tax.rate() != null ? tax.rate() : BigDecimal.ZERO;
                        BigDecimal taxValue = taxBase
                                .multiply(taxRate)
                                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);

                        String rateCode = tax.rateCode() != null ? tax.rateCode() : "";
                        switch (rateCode) {
                            case "0" ->
                                subtotalVat0 = subtotalVat0.add(taxBase);
                            case "2" ->
                                subtotalVat12 = subtotalVat12.add(taxBase);
                            case "4" ->
                                subtotalVat15 = subtotalVat15.add(taxBase);
                            case "6" ->
                                subtotalNonTaxable = subtotalNonTaxable.add(taxBase);
                            case "7" ->
                                subtotalExempt = subtotalExempt.add(taxBase);
                            default -> {
                                /* other rates */ }
                        }
                        vatAmount = vatAmount.add(taxValue);
                    } else if ("3".equals(tax.code())) {
                        BigDecimal taxRate = tax.rate() != null ? tax.rate() : BigDecimal.ZERO;
                        iceAmount = iceAmount.add(
                                discountedTotal.multiply(taxRate)
                                        .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
                    }
                }
            }
        }

        doc.subtotalBeforeTax = subtotalBeforeTax.setScale(2, RoundingMode.HALF_UP);
        doc.totalDiscount = totalDiscount.setScale(2, RoundingMode.HALF_UP);
        doc.subtotalVat0 = subtotalVat0.setScale(2, RoundingMode.HALF_UP);
        doc.subtotalVat12 = subtotalVat12.setScale(2, RoundingMode.HALF_UP);
        doc.subtotalVat15 = subtotalVat15.setScale(2, RoundingMode.HALF_UP);
        doc.subtotalNonTaxable = subtotalNonTaxable.setScale(2, RoundingMode.HALF_UP);
        doc.subtotalExempt = subtotalExempt.setScale(2, RoundingMode.HALF_UP);
        doc.vatAmount = vatAmount.setScale(2, RoundingMode.HALF_UP);
        doc.iceAmount = iceAmount.setScale(2, RoundingMode.HALF_UP);
        doc.totalAmount = subtotalBeforeTax.add(vatAmount).add(iceAmount).setScale(2, RoundingMode.HALF_UP);
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
