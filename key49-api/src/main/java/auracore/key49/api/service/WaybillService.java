package auracore.key49.api.service;

import auracore.key49.api.dto.CreateWaybillRequest;
import auracore.key49.api.dto.CreateWaybillRequest.AddresseeRequest;
import auracore.key49.api.dto.CreateWaybillRequest.ItemRequest;
import auracore.key49.api.exception.BusinessException;
import auracore.key49.api.exception.BusinessException.FieldError;
import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.core.model.enums.IdentificationType;
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
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Servicio de negocio para operaciones sobre guías de remisión electrónicas.
 */

@ApplicationScoped
public class WaybillService {

    private static final Pattern LICENSE_PLATE_PATTERN = Pattern.compile("[A-Za-z0-9\\-]{1,20}");
    private static final Pattern DOC_NUMBER_PATTERN = Pattern.compile("\\d{3}-\\d{3}-\\d{9}");
    private static final Pattern ESTABLISHMENT_CODE_PATTERN = Pattern.compile("\\d{3}");

    @Inject
    Logger log;

    @Inject
    TenantContext tenantContext;

    @Inject
    TenantConnectionManager tcm;

    @Inject
    ObjectMapper objectMapper;

    // ── Crear guía de remisión ──

    public Uni<Document> createWaybill(CreateWaybillRequest request,
            String idempotencyKey, String requestIp) {

        validateCreateRequest(request);

        return tcm.withTenantTransaction(tenantContext.getSchemaName(), session -> {
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                return findByIdempotencyKey(session, idempotencyKey)
                        .chain(existing -> {
                            if (existing != null) {
                                log.infof("Idempotent hit: returning existing waybill %s", existing.id);
                                return Uni.createFrom().item(existing);
                            }
                            return checkUniquenessAndPersist(session, request, idempotencyKey, requestIp);
                        });
            }
            return checkUniquenessAndPersist(session, request, null, requestIp);
        });
    }

    private Uni<Document> findByIdempotencyKey(Mutiny.Session session, String idempotencyKey) {
        return session.createQuery(
                "FROM Document d WHERE d.idempotencyKey = :key AND d.documentType = :docType",
                Document.class)
                .setParameter("key", idempotencyKey)
                .setParameter("docType", DocumentType.WAYBILL.sriCode())
                .getSingleResultOrNull();
    }

    private Uni<Document> checkUniquenessAndPersist(Mutiny.Session session,
            CreateWaybillRequest request, String idempotencyKey, String requestIp) {

        return session.createQuery(
                "SELECT count(d) FROM Document d WHERE d.documentType = :docType "
                        + "AND d.establishment = :est AND d.issuePoint = :pt "
                        + "AND d.sequenceNumber = :seq",
                Long.class)
                .setParameter("docType", DocumentType.WAYBILL.sriCode())
                .setParameter("est", request.establishment())
                .setParameter("pt", request.issuePoint())
                .setParameter("seq", request.sequenceNumber())
                .getSingleResult()
                .chain(count -> {
                    if (count > 0) {
                        return Uni.createFrom().failure(new BusinessException(
                                "DUPLICATE_DOCUMENT",
                                "Waybill %s-%s-%s already exists".formatted(
                                        request.establishment(), request.issuePoint(),
                                        request.sequenceNumber()),
                                409));
                    }
                    return persistNewDocument(session, request, idempotencyKey, requestIp);
                });
    }

    private Uni<Document> persistNewDocument(Mutiny.Session session,
            CreateWaybillRequest request, String idempotencyKey, String requestIp) {

        var doc = new Document();
        doc.documentType = DocumentType.WAYBILL.sriCode();
        doc.establishment = request.establishment();
        doc.issuePoint = request.issuePoint();
        doc.sequenceNumber = request.sequenceNumber();
        doc.issueDate = request.issueDate();

        // For waybills, the "recipient" is the carrier (transportista)
        doc.recipientIdType = request.carrier().idType();
        doc.recipientId = request.carrier().id();
        doc.recipientName = request.carrier().name();
        doc.recipientEmail = request.carrier() != null ? request.carrier().email() : null;

        doc.idempotencyKey = idempotencyKey;
        doc.requestIp = requestIp;
        doc.requestOrigin = "JSON";

        try {
            doc.requestPayload = objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            return Uni.createFrom().failure(new BusinessException(
                    "SERIALIZATION_ERROR", "Failed to serialize request", 500));
        }

        // Waybills have no financial amounts
        doc.subtotalBeforeTax = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        doc.totalDiscount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        doc.vatAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        doc.iceAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        doc.totalAmount = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        doc.createdAt = Instant.now();
        doc.updatedAt = Instant.now();

        log.infof("Creating waybill %s-%s-%s for carrier %s",
                doc.establishment, doc.issuePoint, doc.sequenceNumber, doc.recipientId);

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
        return tcm.withTenantTransaction(tenantContext.getSchemaName(), session ->
                session.find(Document.class, id)
                        .onItem().ifNull().failWith(() -> new BusinessException(
                                "DOCUMENT_NOT_FOUND", "Waybill not found: " + id, 404))
        );
    }

    // ── Listar guías de remisión ──

    public Uni<PagedResult> listWaybills(String status, LocalDate dateFrom,
            LocalDate dateTo, String recipientId, String accessKey,
            int page, int perPage, String sort) {

        int safePage = Math.max(1, page);
        int safePerPage = Math.max(1, Math.min(100, perPage));

        return tcm.withTenantSession(tenantContext.getSchemaName(), session -> {
            var hql = new StringBuilder("FROM Document d WHERE d.documentType = :docType");
            var params = new java.util.LinkedHashMap<String, Object>();
            params.put("docType", DocumentType.WAYBILL.sriCode());

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

    // ── Anular guía de remisión localmente ──

    public Uni<Document> voidWaybill(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            return Uni.createFrom().failure(new BusinessException(
                    "VALIDATION_ERROR", "Void reason is required", 400));
        }

        return tcm.withTenantTransaction(tenantContext.getSchemaName(), session ->
                session.find(Document.class, id)
                        .onItem().ifNull().failWith(() -> new BusinessException(
                                "DOCUMENT_NOT_FOUND", "Document not found: " + id, 404))
                        .chain(doc -> {
                            if (doc.status != DocumentStatus.AUTHORIZED
                                    && doc.status != DocumentStatus.NOTIFIED) {
                                return Uni.createFrom().failure(new BusinessException(
                                        "INVALID_STATE_TRANSITION",
                                        "Cannot void document in state " + doc.status,
                                        409));
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
                            if (doc.status != DocumentStatus.AUTHORIZED
                                    && doc.status != DocumentStatus.NOTIFIED) {
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

    void validateCreateRequest(CreateWaybillRequest request) {
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

        if (request.departureAddress() == null || request.departureAddress().isBlank()) {
            errors.add(new FieldError("departure_address", "Required", "REQUIRED"));
        }

        validateCarrier(request.carrier(), errors);
        validateTransportDates(request, errors);
        validateLicensePlate(request.licensePlate(), errors);
        validateAddressees(request.addressees(), errors);

        if (!errors.isEmpty()) {
            throw new BusinessException("VALIDATION_ERROR", "Request contains invalid fields", 400, errors);
        }
    }

    private void validateCarrier(CreateWaybillRequest.CarrierRequest carrier, List<FieldError> errors) {
        if (carrier == null) {
            errors.add(new FieldError("carrier", "Required", "REQUIRED"));
            return;
        }
        if (carrier.idType() == null || carrier.idType().isBlank()) {
            errors.add(new FieldError("carrier.id_type", "Required", "REQUIRED"));
        } else {
            try {
                var idType = IdentificationType.fromSriCode(carrier.idType());
                // tipoIdentificacionTransportista allows codes 04-08
                int code = Integer.parseInt(carrier.idType());
                if (code < 4 || code > 8) {
                    errors.add(new FieldError("carrier.id_type",
                            "Must be between 04 and 08", "INVALID_FORMAT"));
                }
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError("carrier.id_type",
                        "Invalid identification type code", "INVALID_FORMAT"));
            }
        }
        if (carrier.id() == null || carrier.id().isBlank()) {
            errors.add(new FieldError("carrier.id", "Required", "REQUIRED"));
        }
        if (carrier.name() == null || carrier.name().isBlank()) {
            errors.add(new FieldError("carrier.name", "Required", "REQUIRED"));
        }
    }

    private void validateTransportDates(CreateWaybillRequest request, List<FieldError> errors) {
        if (request.transportStartDate() == null) {
            errors.add(new FieldError("transport_start_date", "Required", "REQUIRED"));
        }
        if (request.transportEndDate() == null) {
            errors.add(new FieldError("transport_end_date", "Required", "REQUIRED"));
        }
        if (request.transportStartDate() != null && request.transportEndDate() != null
                && request.transportEndDate().isBefore(request.transportStartDate())) {
            errors.add(new FieldError("transport_end_date",
                    "Must not be before transport start date", "INVALID_DATE_RANGE"));
        }
    }

    private void validateLicensePlate(String licensePlate, List<FieldError> errors) {
        if (licensePlate == null || licensePlate.isBlank()) {
            errors.add(new FieldError("license_plate", "Required", "REQUIRED"));
        } else if (!LICENSE_PLATE_PATTERN.matcher(licensePlate).matches()) {
            errors.add(new FieldError("license_plate",
                    "Must be 1-20 alphanumeric characters", "INVALID_FORMAT"));
        }
    }

    private void validateAddressees(List<AddresseeRequest> addressees, List<FieldError> errors) {
        if (addressees == null || addressees.isEmpty()) {
            errors.add(new FieldError("addressees",
                    "At least one addressee is required", "REQUIRED"));
            return;
        }

        for (int i = 0; i < addressees.size(); i++) {
            var addr = addressees.get(i);
            String prefix = "addressees[%d]".formatted(i);

            if (addr.id() == null || addr.id().isBlank()) {
                errors.add(new FieldError(prefix + ".id", "Required", "REQUIRED"));
            }
            if (addr.name() == null || addr.name().isBlank()) {
                errors.add(new FieldError(prefix + ".name", "Required", "REQUIRED"));
            }
            if (addr.address() == null || addr.address().isBlank()) {
                errors.add(new FieldError(prefix + ".address", "Required", "REQUIRED"));
            }
            if (addr.transferReason() == null || addr.transferReason().isBlank()) {
                errors.add(new FieldError(prefix + ".transfer_reason", "Required", "REQUIRED"));
            }

            if (addr.destinationEstablishment() != null && !addr.destinationEstablishment().isBlank()
                    && !ESTABLISHMENT_CODE_PATTERN.matcher(addr.destinationEstablishment()).matches()) {
                errors.add(new FieldError(prefix + ".destination_establishment",
                        "Must be 3 digits", "INVALID_FORMAT"));
            }

            if (addr.supportDocumentNumber() != null && !addr.supportDocumentNumber().isBlank()
                    && !DOC_NUMBER_PATTERN.matcher(addr.supportDocumentNumber()).matches()) {
                errors.add(new FieldError(prefix + ".support_document_number",
                        "Must match format 000-000-000000000", "INVALID_FORMAT"));
            }

            validateItems(addr.items(), prefix, errors);
        }
    }

    private void validateItems(List<ItemRequest> items, String prefix, List<FieldError> errors) {
        if (items == null || items.isEmpty()) {
            errors.add(new FieldError(prefix + ".items",
                    "At least one item is required", "REQUIRED"));
            return;
        }
        for (int j = 0; j < items.size(); j++) {
            var item = items.get(j);
            String ip = prefix + ".items[%d]".formatted(j);

            if (item.description() == null || item.description().isBlank()) {
                errors.add(new FieldError(ip + ".description", "Required", "REQUIRED"));
            }
            if (item.quantity() == null || item.quantity().compareTo(BigDecimal.ZERO) <= 0) {
                errors.add(new FieldError(ip + ".quantity", "Must be positive", "INVALID_FORMAT"));
            }
            if (item.additionalDetails() != null && item.additionalDetails().size() > 3) {
                errors.add(new FieldError(ip + ".additional_details",
                        "Maximum 3 additional details allowed", "MAX_EXCEEDED"));
            }
        }
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
            case "status" -> "d.status";
            default -> "d.issueDate";
        };
        return " ORDER BY " + column + (desc ? " DESC" : " ASC");
    }

    public record PagedResult(List<Document> items, long total) {
    }
}
