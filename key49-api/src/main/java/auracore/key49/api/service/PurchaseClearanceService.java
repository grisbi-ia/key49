package auracore.key49.api.service;

import auracore.key49.api.dto.CreatePurchaseClearanceRequest;
import auracore.key49.api.dto.CreatePurchaseClearanceRequest.ItemRequest;
import auracore.key49.api.dto.CreatePurchaseClearanceRequest.TaxRequest;
import auracore.key49.api.exception.BusinessException;
import auracore.key49.api.exception.BusinessException.FieldError;
import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.core.model.enums.IdentificationType;
import auracore.key49.core.model.enums.PaymentMethod;
import auracore.key49.core.model.enums.TaxType;
import auracore.key49.core.model.enums.VatRate;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.core.tenant.TenantContext;
import auracore.key49.core.validation.SriValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;
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

/**
 * Servicio de negocio para operaciones sobre liquidaciones de compra
 * electrónicas.
 */

@ApplicationScoped
public class PurchaseClearanceService {

    @Inject
    Logger log;

    @Inject
    TenantContext tenantContext;

    @Inject
    TenantConnectionManager tcm;

    @Inject
    ObjectMapper objectMapper;

    // ── Crear liquidación de compra ──

    public Uni<Document> createPurchaseClearance(CreatePurchaseClearanceRequest request, String idempotencyKey,
                                                  String requestIp) {
        validateCreateRequest(request);

        return tcm.withTenantTransaction(tenantContext.getSchemaName(), session -> {
            Uni<Document> existingCheck = idempotencyKey != null
                    ? session.createQuery("FROM Document d WHERE d.idempotencyKey = :key", Document.class)
                    .setParameter("key", idempotencyKey)
                    .getSingleResultOrNull()
                    : Uni.createFrom().nullItem();

            return existingCheck.chain(existing -> {
                if (existing != null) {
                    log.infof("Idempotent request found | docId=%s key=%s", existing.id, idempotencyKey);
                    return Uni.createFrom().item(existing);
                }
                return checkUniquenessAndPersist(session, request, idempotencyKey, requestIp);
            });
        });
    }

    private Uni<Document> checkUniquenessAndPersist(Mutiny.Session session, CreatePurchaseClearanceRequest request,
                                                     String idempotencyKey, String requestIp) {
        return session.createQuery(
                        "FROM Document d WHERE d.documentType = :dt AND d.establishment = :est " +
                                "AND d.issuePoint = :ip AND d.sequenceNumber = :sn", Document.class)
                .setParameter("dt", DocumentType.PURCHASE_CLEARANCE.sriCode())
                .setParameter("est", request.establishment())
                .setParameter("ip", request.issuePoint())
                .setParameter("sn", request.sequenceNumber())
                .getSingleResultOrNull()
                .chain(duplicate -> {
                    if (duplicate != null) {
                        return Uni.createFrom().failure(new BusinessException(
                                "DUPLICATE_DOCUMENT",
                                "Document %s-%s-%s already exists".formatted(
                                        request.establishment(), request.issuePoint(), request.sequenceNumber()),
                                409));
                    }
                    return persistNewDocument(session, request, idempotencyKey, requestIp);
                });
    }

    private Uni<Document> persistNewDocument(Mutiny.Session session, CreatePurchaseClearanceRequest request,
                                              String idempotencyKey, String requestIp) {
        var doc = new Document();
        doc.documentType = DocumentType.PURCHASE_CLEARANCE.sriCode();
        doc.establishment = request.establishment();
        doc.issuePoint = request.issuePoint();
        doc.sequenceNumber = request.sequenceNumber();
        doc.issueDate = request.issueDate();
        doc.recipientIdType = request.supplier().idType();
        doc.recipientId = request.supplier().id();
        doc.recipientName = request.supplier().name();
        doc.recipientEmail = request.supplier().email();
        doc.recipientAddress = request.supplier().address();
        doc.recipientPhone = request.supplier().phone();
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
            return Uni.createFrom().failure(new RuntimeException("Failed to serialize request payload", e));
        }

        return session.persist(doc)
                .chain(() -> {
                    var outbox = OutboxEvent.create(doc.id, "doc.sign", "{}");
                    return session.persist(outbox);
                })
                .chain(session::flush)
                .replaceWith(doc);
    }

    // ── Consultar por ID ──

    public Uni<Document> findById(UUID id) {
        return tcm.withTenantSession(tenantContext.getSchemaName(), session ->
                session.find(Document.class, id)
        ).onItem().ifNull().failWith(() -> new BusinessException(
                "DOCUMENT_NOT_FOUND", "Document not found: " + id, 404));
    }

    // ── Listar con filtros y paginación ──

    public Uni<PagedResult> listPurchaseClearances(String status, LocalDate dateFrom, LocalDate dateTo,
                                                    String recipientId, String accessKey,
                                                    int page, int perPage, String sort) {
        int safePage = Math.max(1, page);
        int safePerPage = Math.max(1, Math.min(100, perPage));

        return tcm.withTenantSession(tenantContext.getSchemaName(), session -> {
            var hql = new StringBuilder("FROM Document d WHERE d.documentType = :dt");
            Map<String, Object> params = new HashMap<>();
            params.put("dt", DocumentType.PURCHASE_CLEARANCE.sriCode());

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

            var countQuery = session.createQuery(countHql, Long.class);
            var dataQuery = session.createQuery(hql.toString(), Document.class)
                    .setFirstResult((safePage - 1) * safePerPage)
                    .setMaxResults(safePerPage);

            params.forEach((k, v) -> {
                countQuery.setParameter(k, v);
                dataQuery.setParameter(k, v);
            });

            return countQuery.getSingleResult()
                    .chain(total -> dataQuery.getResultList()
                            .map(docs -> new PagedResult(docs, total)));
        });
    }

    // ── Anular localmente ──

    public Uni<Document> voidPurchaseClearance(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            return Uni.createFrom().failure(new BusinessException(
                    "VALIDATION_ERROR", "Void reason is required", 400));
        }

        return tcm.withTenantTransaction(tenantContext.getSchemaName(), session ->
                session.find(Document.class, id)
                        .onItem().ifNull().failWith(() -> new BusinessException(
                                "DOCUMENT_NOT_FOUND", "Document not found: " + id, 404))
                        .chain(doc -> {
                            if (doc.status != DocumentStatus.AUTHORIZED && doc.status != DocumentStatus.NOTIFIED) {
                                return Uni.createFrom().failure(new BusinessException(
                                        "INVALID_STATE_TRANSITION",
                                        "Cannot void document in state " + doc.status,
                                        409));
                            }

                            LocalDate today = LocalDate.now(Key49Constants.EC_ZONE);
                            LocalDate deadline = doc.issueDate.plusMonths(1).withDayOfMonth(7);
                            if (today.isAfter(deadline)) {
                                return Uni.createFrom().failure(new BusinessException(
                                        "VOID_PERIOD_EXPIRED",
                                        "Void period has expired (deadline was %s)".formatted(deadline),
                                        422));
                            }

                            doc.transitionTo(DocumentStatus.VOIDED);
                            doc.voidedAt = Instant.now();
                            doc.voidReason = reason;
                            doc.updatedAt = Instant.now();

                            return Uni.createFrom().item(doc);
                        })
        );
    }

    // ── Reenviar email ──

    public Uni<Instant> resendEmail(UUID id) {
        return tcm.withTenantTransaction(tenantContext.getSchemaName(), session ->
                session.find(Document.class, id)
                        .onItem().ifNull().failWith(() -> new BusinessException(
                                "DOCUMENT_NOT_FOUND", "Document not found: " + id, 404))
                        .chain(doc -> {
                            if (doc.status != DocumentStatus.AUTHORIZED && doc.status != DocumentStatus.NOTIFIED) {
                                return Uni.createFrom().failure(new BusinessException(
                                        "INVALID_STATE_TRANSITION",
                                        "Cannot resend email for document in state " + doc.status,
                                        409));
                            }
                            if (doc.recipientEmail == null || doc.recipientEmail.isBlank()) {
                                return Uni.createFrom().failure(new BusinessException(
                                        "VALIDATION_ERROR",
                                        "Document has no recipient email configured",
                                        400));
                            }

                            var outbox = OutboxEvent.create(doc.id, "doc.notify", "{}");
                            return session.persist(outbox)
                                    .replaceWith(Instant.now());
                        })
        );
    }

    // ── Validaciones ──

    void validateCreateRequest(CreatePurchaseClearanceRequest request) {
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

        validateSupplier(request.supplier(), errors);
        validateItems(request.items(), errors);
        validatePayments(request.payments(), errors);

        if (!errors.isEmpty()) {
            throw new BusinessException("VALIDATION_ERROR", "Request contains invalid fields", 400, errors);
        }
    }

    private void validateSupplier(CreatePurchaseClearanceRequest.SupplierRequest supplier, List<FieldError> errors) {
        if (supplier == null) {
            errors.add(new FieldError("supplier", "Required", "REQUIRED"));
            return;
        }
        if (supplier.idType() == null || supplier.idType().isBlank()) {
            errors.add(new FieldError("supplier.id_type", "Required", "REQUIRED"));
        } else {
            try {
                IdentificationType.fromSriCode(supplier.idType());
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError("supplier.id_type", "Invalid identification type code", "INVALID_FORMAT"));
            }
        }
        if (!SriValidator.isValidIdentification(supplier.idType(), supplier.id())) {
            errors.add(new FieldError("supplier.id",
                    "Invalid identification for type " + supplier.idType(), "INVALID_SUPPLIER_ID"));
        }
        if (supplier.name() == null || supplier.name().isBlank()) {
            errors.add(new FieldError("supplier.name", "Required", "REQUIRED"));
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

    private void validatePayments(List<CreatePurchaseClearanceRequest.PaymentRequest> payments,
                                   List<FieldError> errors) {
        if (payments == null || payments.isEmpty()) {
            errors.add(new FieldError("payments", "At least one payment is required", "REQUIRED"));
            return;
        }
        for (int i = 0; i < payments.size(); i++) {
            var payment = payments.get(i);
            if (payment.paymentMethod() == null) {
                errors.add(new FieldError("payments[%d].payment_method".formatted(i), "Required", "REQUIRED"));
                continue;
            }
            try {
                PaymentMethod.fromSriCode(payment.paymentMethod());
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError("payments[%d].payment_method".formatted(i),
                        "Invalid payment method code", "INVALID_FORMAT"));
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
                            case "0" -> subtotalVat0 = subtotalVat0.add(taxBase);
                            case "2" -> subtotalVat12 = subtotalVat12.add(taxBase);
                            case "4" -> subtotalVat15 = subtotalVat15.add(taxBase);
                            case "6" -> subtotalNonTaxable = subtotalNonTaxable.add(taxBase);
                            case "7" -> subtotalExempt = subtotalExempt.add(taxBase);
                            default -> { /* other rates: 14%, 5%, etc. */ }
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
            case "issue_date" -> "d.issueDate";
            case "created_at" -> "d.createdAt";
            case "total_amount" -> "d.totalAmount";
            case "status" -> "d.status";
            default -> "d.issueDate";
        };
        return " ORDER BY " + column + (desc ? " DESC" : " ASC");
    }

    /**
     * Resultado paginado para uso interno.
     */
    public record PagedResult(List<Document> items, long total) {
    }
}
