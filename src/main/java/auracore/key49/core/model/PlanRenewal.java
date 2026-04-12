package auracore.key49.core.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "plan_renewals", schema = "public")
public class PlanRenewal extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "renewal_id")
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "plan_type", nullable = false, length = 20)
    public String planType;

    @Column(name = "document_quota", nullable = false)
    public int documentQuota;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    public BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "payment_proof_path", length = 500)
    public String paymentProofPath;

    @Column(name = "status", nullable = false, length = 20)
    public String status = "pending";

    @Column(name = "approved_by", length = 200)
    public String approvedBy;

    @Column(name = "approved_at")
    public Instant approvedAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    public String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;
}
