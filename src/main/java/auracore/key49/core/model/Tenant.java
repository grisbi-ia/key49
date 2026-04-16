package auracore.key49.core.model;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

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

    @Column(name = "pending_certificate_p12")
    public byte[] pendingCertificateP12;

    @Column(name = "pending_certificate_password_enc")
    public byte[] pendingCertificatePasswordEnc;

    @Column(name = "pending_certificate_subject", length = 500)
    public String pendingCertificateSubject;

    @Column(name = "pending_certificate_expiration")
    public Instant pendingCertificateExpiration;

    @Column(name = "pending_certificate_serial", length = 100)
    public String pendingCertificateSerial;

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

    // ── Plan & Quotas ──
    @Column(name = "plan_type", nullable = false, length = 20)
    @ColumnDefault("'demo'")
    public String planType = "demo";

    @Column(name = "document_quota", nullable = false)
    @ColumnDefault("25")
    public int documentQuota = 25;

    @Column(name = "documents_used", nullable = false)
    @ColumnDefault("0")
    public int documentsUsed = 0;

    @Column(name = "plan_starts_at")
    public Instant planStartsAt;

    @Column(name = "plan_expires_at")
    public Instant planExpiresAt;

    // ── Email provider per-tenant ──
    @Column(name = "email_provider", nullable = false, length = 20)
    @ColumnDefault("'smtp'")
    public String emailProvider = "smtp";

    // Plunk API key cifrada con AES-256-GCM (misma clave maestra que el certificado)
    @Column(name = "plunk_api_key_enc")
    public byte[] plunkApiKeyEnc;

    // ── SMTP per-tenant ──
    @Column(name = "smtp_host", length = 255)
    public String smtpHost;

    @Column(name = "smtp_port")
    public Integer smtpPort;

    @Column(name = "smtp_user", length = 255)
    public String smtpUser;

    @Column(name = "smtp_password_enc")
    public byte[] smtpPasswordEnc;

    @Column(name = "smtp_from", length = 255)
    public String smtpFrom;

    @Column(name = "email_notifications_enabled", nullable = false)
    @ColumnDefault("true")
    public boolean emailNotificationsEnabled = true;

    @Column(name = "notify_final_consumer", nullable = false)
    @ColumnDefault("true")
    public boolean notifyFinalConsumer = true;

    // ── Portal authentication ──
    @Column(name = "email", length = 255)
    public String email;

    @Column(name = "email_verified", nullable = false)
    @ColumnDefault("false")
    public boolean emailVerified;

    @Column(name = "portal_password_hash", length = 255)
    public String portalPasswordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
