package auracore.key49.core.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import auracore.key49.core.model.enums.DocumentStatus;
import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "documents")
public class Document extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "document_id")
    public UUID id;

    // ── Document identification ──

    @Column(name = "document_type", nullable = false, length = 2)
    public String documentType;

    @Column(name = "establishment", nullable = false, length = 3)
    public String establishment;

    @Column(name = "issue_point", nullable = false, length = 3)
    public String issuePoint;

    @Column(name = "sequence_number", nullable = false, length = 9)
    public String sequenceNumber;

    @Column(name = "access_key", length = 49, unique = true)
    public String accessKey;

    @Column(name = "authorization_number", length = 49)
    public String authorizationNumber;

    // ── Request origin ──

    @Column(name = "request_origin", nullable = false, length = 10)
    public String requestOrigin = "JSON";

    // ── Recipient ──

    @Column(name = "recipient_id_type", nullable = false, length = 2)
    public String recipientIdType;

    @Column(name = "recipient_id", nullable = false, length = 20)
    public String recipientId;

    @Column(name = "recipient_name", nullable = false, length = 300)
    public String recipientName;

    @Column(name = "recipient_email", length = 500)
    public String recipientEmail;

    @Column(name = "recipient_address", length = 300)
    public String recipientAddress;

    @Column(name = "recipient_phone", length = 50)
    public String recipientPhone;

    // ── Amounts ──

    @Column(name = "subtotal_before_tax", nullable = false, precision = 14, scale = 2)
    public BigDecimal subtotalBeforeTax = BigDecimal.ZERO;

    @Column(name = "total_discount", nullable = false, precision = 14, scale = 2)
    public BigDecimal totalDiscount = BigDecimal.ZERO;

    @Column(name = "subtotal_vat_0", nullable = false, precision = 14, scale = 2)
    public BigDecimal subtotalVat0 = BigDecimal.ZERO;

    @Column(name = "subtotal_vat_12", nullable = false, precision = 14, scale = 2)
    public BigDecimal subtotalVat12 = BigDecimal.ZERO;

    @Column(name = "subtotal_vat_15", nullable = false, precision = 14, scale = 2)
    public BigDecimal subtotalVat15 = BigDecimal.ZERO;

    @Column(name = "subtotal_non_taxable", nullable = false, precision = 14, scale = 2)
    public BigDecimal subtotalNonTaxable = BigDecimal.ZERO;

    @Column(name = "subtotal_exempt", nullable = false, precision = 14, scale = 2)
    public BigDecimal subtotalExempt = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "ice_amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal iceAmount = BigDecimal.ZERO;

    @Column(name = "tip", nullable = false, precision = 14, scale = 2)
    public BigDecimal tip = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    public BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 15)
    public String currency = "DOLAR";

    // ── Dates ──

    @Column(name = "issue_date", nullable = false)
    public LocalDate issueDate;

    @Column(name = "authorization_date")
    public Instant authorizationDate;

    @Column(name = "sri_submission_date")
    public Instant sriSubmissionDate;

    // ── Pipeline status ──

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    public DocumentStatus status = DocumentStatus.CREATED;

    // ── Processing ──

    @Column(name = "retry_count", nullable = false)
    public short retryCount;

    @Column(name = "max_retries", nullable = false)
    public short maxRetries = 6;

    @Column(name = "next_retry_at")
    public Instant nextRetryAt;

    @Column(name = "last_error_code", length = 10)
    public String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "text")
    public String lastErrorMessage;

    @Column(name = "sri_messages", columnDefinition = "jsonb")
    public String sriMessages;

    // ── Storage (MinIO paths) ──

    @Column(name = "unsigned_xml_path", length = 500)
    public String unsignedXmlPath;

    @Column(name = "signed_xml_path", length = 500)
    public String signedXmlPath;

    @Column(name = "authorized_xml_path", length = 500)
    public String authorizedXmlPath;

    @Column(name = "ride_path", length = 500)
    public String ridePath;

    // ── Original request data ──

    @Column(name = "request_payload", columnDefinition = "jsonb")
    public String requestPayload;

    @Column(name = "original_xml", columnDefinition = "text")
    public String originalXml;

    @Column(name = "request_ip", length = 45)
    public String requestIp;

    @Column(name = "idempotency_key", length = 50, unique = true)
    public String idempotencyKey;

    // ── Email delivery ──

    @Column(name = "email_sent_at")
    public Instant emailSentAt;

    @Column(name = "email_status", length = 20)
    public String emailStatus;

    @Column(name = "email_error", length = 500)
    public String emailError;

    // ── Void/cancellation ──

    @Column(name = "voided_at")
    public Instant voidedAt;

    @Column(name = "void_reason", length = 500)
    public String voidReason;

    // ── Metadata ──

    @Version
    @Column(name = "version", nullable = false)
    public int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    /**
     * Transiciona el documento al estado destino si la transición es válida.
     *
     * @throws InvalidStateTransitionException si la transición no está permitida
     */
    public void transitionTo(DocumentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(status, target);
        }
        this.status = target;
    }
}
