package auracore.key49.core.model;

import java.time.Instant;
import java.util.UUID;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "tenants", schema = "public")
public class Tenant extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "tenant_id")
    public UUID id;

    @Column(name = "ruc", nullable = false, length = 13)
    public String ruc;

    @Column(name = "legal_name", nullable = false, length = 300)
    public String legalName;

    @Column(name = "trade_name", length = 300)
    public String tradeName;

    @Column(name = "main_address", nullable = false, length = 300)
    public String mainAddress;

    @Column(name = "required_accounting", nullable = false)
    public boolean requiredAccounting;

    @Column(name = "special_taxpayer", length = 20)
    public String specialTaxpayer;

    @Column(name = "micro_enterprise_regime", nullable = false)
    public boolean microEnterpriseRegime;

    @Column(name = "withholding_agent", length = 10)
    public String withholdingAgent;

    @Column(name = "environment", nullable = false, length = 10)
    public String environment = "test";

    @Column(name = "emission_type", nullable = false)
    public short emissionType = 1;

    @Column(name = "logo_url", length = 500)
    public String logoUrl;

    @Column(name = "certificate_p12")
    public byte[] certificateP12;

    @Column(name = "certificate_password_enc")
    public byte[] certificatePasswordEnc;

    @Column(name = "certificate_subject", length = 500)
    public String certificateSubject;

    @Column(name = "certificate_expiration")
    public Instant certificateExpiration;

    @Column(name = "certificate_serial", length = 100)
    public String certificateSerial;

    @Column(name = "webhook_url", length = 500)
    public String webhookUrl;

    @Column(name = "webhook_secret", length = 100)
    public String webhookSecret;

    @Column(name = "rate_limit_rpm", nullable = false)
    public int rateLimitRpm = 100;

    @Column(name = "rate_limit_write_rpm", nullable = false)
    @ColumnDefault("30")
    public int rateLimitWriteRpm = 30;

    @Column(name = "rate_limit_read_rpm", nullable = false)
    @ColumnDefault("200")
    public int rateLimitReadRpm = 200;

    @Column(name = "email_sender_name", length = 200)
    public String emailSenderName;

    @Column(name = "reply_email", length = 200)
    public String replyEmail;

    @Column(name = "schema_name", nullable = false, length = 63)
    public String schemaName;

    @Column(name = "status", nullable = false, length = 20)
    public String status = "active";

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
