package auracore.key49.core.model;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_keys", schema = "public")
public class ApiKey extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "api_key_id")
    public UUID id;

    @Column(name = "tenant_id", nullable = false)
    public UUID tenantId;

    @Column(name = "key_prefix", nullable = false, length = 8)
    public String keyPrefix;

    @Column(name = "key_hash", nullable = false, length = 128)
    public String keyHash;

    @Column(name = "name", nullable = false, length = 100)
    public String name;

    @Column(name = "permissions", nullable = false, length = 500)
    public String permissions = "*";

    @Column(name = "last_used_at")
    public Instant lastUsedAt;

    @Column(name = "expires_at")
    public Instant expiresAt;

    @Column(name = "status", nullable = false, length = 20)
    public String status = "active";

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;
}
