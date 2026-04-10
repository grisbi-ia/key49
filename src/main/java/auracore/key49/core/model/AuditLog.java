package auracore.key49.core.model;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;


@Entity
@Table(name = "audit_log", schema = "public")
public class AuditLog extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "audit_log_id")
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "actor", nullable = false, length = 100)
    public String actor;

    @Column(name = "action", nullable = false, length = 50)
    public String action;

    @Column(name = "resource", nullable = false, length = 50)
    public String resource;

    @Column(name = "resource_id")
    public UUID resourceId;

    @Column(name = "ip_address", length = 45)
    public String ipAddress;

    @Column(name = "details", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public String details;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;
}
