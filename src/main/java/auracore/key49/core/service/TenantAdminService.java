package auracore.key49.core.service;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantSchemaResolver;
import auracore.key49.core.validation.SriValidator;
import auracore.key49.signer.CertificateCacheService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Servicio de administración de tenants: CRUD en esquema público.
 */
@ApplicationScoped
public class TenantAdminService {

    @Inject
    TenantRepository tenantRepository;

    @Inject
    TenantCacheService tenantCacheService;

    @Inject
    CertificateCacheService certificateCacheService;

    @Inject
    DataSource dataSource;

    // ── Crear tenant con provisioning automático ──
    @Transactional
    public Tenant create(CreateTenantData data) {
        validateCreateData(data);
        TenantSchemaResolver.validate(data.schemaName());

        Tenant existing = tenantRepository.findByRuc(data.ruc());
        if (existing != null) {
            throw new TenantException("DUPLICATE_RUC",
                    "A tenant with RUC " + data.ruc() + " already exists", 409);
        }

        existing = tenantRepository.findBySchemaName(data.schemaName());
        if (existing != null) {
            throw new TenantException("DUPLICATE_SCHEMA",
                    "A tenant with schema_name '" + data.schemaName() + "' already exists", 409);
        }

        var tenant = new Tenant();
        tenant.ruc = data.ruc();
        tenant.legalName = data.legalName();
        tenant.tradeName = data.tradeName();
        tenant.mainAddress = data.mainAddress();
        tenant.requiredAccounting = data.requiredAccounting();
        tenant.specialTaxpayer = data.specialTaxpayer();
        tenant.microEnterpriseRegime = data.microEnterpriseRegime();
        tenant.withholdingAgent = data.withholdingAgent();
        tenant.environment = data.environment() != null ? data.environment() : "test";
        tenant.schemaName = data.schemaName();
        tenant.status = "pending";
        tenant.createdAt = Instant.now();
        tenant.updatedAt = Instant.now();

        Log.infof("Creating tenant | ruc=%s schema=%s", data.ruc(), data.schemaName());
        tenantRepository.persist(tenant);
        tenantRepository.flush();

        // Provisioning: clonar esquema desde tenant_template
        try {
            cloneSchema(data.schemaName());
            tenant.status = "active";
            tenant.updatedAt = Instant.now();
            Log.infof("Tenant provisioned | id=%s schema=%s status=active", tenant.id, data.schemaName());
            tenantCacheService.invalidate(tenant.id, tenant.schemaName);
        } catch (Exception e) {
            tenant.status = "failed";
            tenant.updatedAt = Instant.now();
            Log.errorf(e, "Tenant provisioning failed | id=%s schema=%s", tenant.id, data.schemaName());
            throw new TenantException("PROVISIONING_FAILED",
                    "Schema provisioning failed for '" + data.schemaName() + "': " + e.getMessage(), 500);
        }

        return tenant;
    }

    /**
     * Ejecuta clone_schema() vía JDBC directo. Usa conexión separada para que
     * el DDL (CREATE SCHEMA) sea visible inmediatamente, independiente de la
     * transacción JPA.
     */
    private void cloneSchema(String schemaName) {
        TenantSchemaResolver.validate(schemaName);
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement("SELECT clone_schema('tenant_template', ?)")) {
            ps.setString(1, schemaName);
            ps.execute();
        } catch (SQLException e) {
            throw new RuntimeException("clone_schema failed: " + e.getMessage(), e);
        }
    }

    // ── Obtener tenant por ID ──
    public Tenant findById(UUID id) {
        Tenant tenant = tenantRepository.findById(id);
        if (tenant == null) {
            throw new TenantException("TENANT_NOT_FOUND",
                    "Tenant not found: " + id, 404);
        }
        return tenant;
    }

    // ── Listar tenants con paginación ──
    public TenantListResult listAll(int page, int perPage, Optional<String> statusFilter) {
        String query = statusFilter.map(s -> "status = ?1").orElse("1=1");
        Object[] params = statusFilter.map(s -> new Object[]{s}).orElse(new Object[]{});

        long total = statusFilter.isPresent()
                ? tenantRepository.count("status", statusFilter.get())
                : tenantRepository.count();

        List<Tenant> items = tenantRepository.find(query, params)
                .page(page - 1, perPage)
                .list();

        return new TenantListResult(items, total);
    }

    // ── Actualizar tenant ──
    @Transactional
    public Tenant update(UUID id, UpdateTenantData data) {
        Tenant tenant = tenantRepository.findById(id);
        if (tenant == null) {
            throw new TenantException("TENANT_NOT_FOUND",
                    "Tenant not found: " + id, 404);
        }

        if (data.legalName() != null) {
            tenant.legalName = data.legalName();
        }
        if (data.tradeName() != null) {
            tenant.tradeName = data.tradeName();
        }
        if (data.mainAddress() != null) {
            tenant.mainAddress = data.mainAddress();
        }
        if (data.requiredAccounting() != null) {
            tenant.requiredAccounting = data.requiredAccounting();
        }
        if (data.specialTaxpayer() != null) {
            tenant.specialTaxpayer = data.specialTaxpayer();
        }
        if (data.microEnterpriseRegime() != null) {
            tenant.microEnterpriseRegime = data.microEnterpriseRegime();
        }
        if (data.withholdingAgent() != null) {
            tenant.withholdingAgent = data.withholdingAgent();
        }
        if (data.environment() != null) {
            tenant.environment = data.environment();
        }
        if (data.webhookUrl() != null) {
            tenant.webhookUrl = data.webhookUrl();
        }
        if (data.webhookSecret() != null) {
            tenant.webhookSecret = data.webhookSecret();
        }
        if (data.rateLimitRpm() != null) {
            tenant.rateLimitRpm = data.rateLimitRpm();
        }
        if (data.rateLimitWriteRpm() != null) {
            tenant.rateLimitWriteRpm = data.rateLimitWriteRpm();
        }
        if (data.rateLimitReadRpm() != null) {
            tenant.rateLimitReadRpm = data.rateLimitReadRpm();
        }
        if (data.emailSenderName() != null) {
            tenant.emailSenderName = data.emailSenderName();
        }
        if (data.replyEmail() != null) {
            tenant.replyEmail = data.replyEmail();
        }
        if (data.status() != null) {
            tenant.status = data.status();
        }
        tenant.updatedAt = Instant.now();

        Log.infof("Updated tenant | id=%s ruc=%s", id, tenant.ruc);
        tenantCacheService.invalidate(id, tenant.schemaName);
        return tenant;
    }

    // ── Subir certificado ──
    @Transactional
    public Tenant uploadCertificate(UUID id, byte[] encryptedP12, byte[] encryptedPassword,
            String subject, Instant expiration, String serial) {
        Tenant tenant = tenantRepository.findById(id);
        if (tenant == null) {
            throw new TenantException("TENANT_NOT_FOUND",
                    "Tenant not found: " + id, 404);
        }

        tenant.certificateP12 = encryptedP12;
        tenant.certificatePasswordEnc = encryptedPassword;
        tenant.certificateSubject = subject;
        tenant.certificateExpiration = expiration;
        tenant.certificateSerial = serial;
        tenant.updatedAt = Instant.now();

        Log.infof("Certificate uploaded | tenantId=%s subject=%s expires=%s",
                id, subject, expiration);
        tenantCacheService.invalidate(id, tenant.schemaName);
        certificateCacheService.invalidate(id);
        return tenant;
    }

    // ── Rotar certificado (sin downtime) ──
    @Transactional
    public Tenant rotateCertificate(UUID id, byte[] encryptedP12, byte[] encryptedPassword,
            String subject, Instant expiration, String serial) {
        Tenant tenant = tenantRepository.findById(id);
        if (tenant == null) {
            throw new TenantException("TENANT_NOT_FOUND",
                    "Tenant not found: " + id, 404);
        }

        tenant.pendingCertificateP12 = encryptedP12;
        tenant.pendingCertificatePasswordEnc = encryptedPassword;
        tenant.pendingCertificateSubject = subject;
        tenant.pendingCertificateExpiration = expiration;
        tenant.pendingCertificateSerial = serial;
        tenant.updatedAt = Instant.now();

        Log.infof("Certificate rotation started | tenantId=%s pending_subject=%s expires=%s",
                id, subject, expiration);
        return tenant;
    }

    // ── Activar certificado pendiente ──
    @Transactional
    public Tenant activateCertificate(UUID id) {
        Tenant tenant = tenantRepository.findById(id);
        if (tenant == null) {
            throw new TenantException("TENANT_NOT_FOUND",
                    "Tenant not found: " + id, 404);
        }
        if (tenant.pendingCertificateP12 == null) {
            throw new TenantException("NO_PENDING_CERTIFICATE",
                    "No pending certificate to activate for tenant: " + id, 422);
        }

        tenant.certificateP12 = tenant.pendingCertificateP12;
        tenant.certificatePasswordEnc = tenant.pendingCertificatePasswordEnc;
        tenant.certificateSubject = tenant.pendingCertificateSubject;
        tenant.certificateExpiration = tenant.pendingCertificateExpiration;
        tenant.certificateSerial = tenant.pendingCertificateSerial;

        tenant.pendingCertificateP12 = null;
        tenant.pendingCertificatePasswordEnc = null;
        tenant.pendingCertificateSubject = null;
        tenant.pendingCertificateExpiration = null;
        tenant.pendingCertificateSerial = null;
        tenant.updatedAt = Instant.now();

        Log.infof("Certificate activated | tenantId=%s subject=%s expires=%s",
                id, tenant.certificateSubject, tenant.certificateExpiration);
        tenantCacheService.invalidate(id, tenant.schemaName);
        certificateCacheService.invalidate(id);
        return tenant;
    }

    // ── Validaciones ──
    private void validateCreateData(CreateTenantData data) {
        if (data.ruc() == null || !SriValidator.isValidRuc(data.ruc())) {
            throw new TenantException("VALIDATION_ERROR", "Invalid RUC: " + data.ruc(), 400);
        }
        if (data.legalName() == null || data.legalName().isBlank()) {
            throw new TenantException("VALIDATION_ERROR", "legal_name is required", 400);
        }
        if (data.mainAddress() == null || data.mainAddress().isBlank()) {
            throw new TenantException("VALIDATION_ERROR", "main_address is required", 400);
        }
        if (data.schemaName() == null || data.schemaName().isBlank()) {
            throw new TenantException("VALIDATION_ERROR", "schema_name is required", 400);
        }
        if (!data.schemaName().matches("^[a-z0-9_]+$") || data.schemaName().length() > 63) {
            throw new TenantException("VALIDATION_ERROR",
                    "schema_name must match [a-z0-9_]+ and be max 63 chars", 400);
        }
        if (data.environment() != null && !"test".equals(data.environment())
                && !"production".equals(data.environment())) {
            throw new TenantException("VALIDATION_ERROR",
                    "environment must be 'test' or 'production'", 400);
        }
    }

    // ── Records ──
    public record CreateTenantData(
            String ruc,
            String legalName,
            String tradeName,
            String mainAddress,
            boolean requiredAccounting,
            String specialTaxpayer,
            boolean microEnterpriseRegime,
            String withholdingAgent,
            String environment,
            String schemaName) {

    }

    public record UpdateTenantData(
            String legalName,
            String tradeName,
            String mainAddress,
            Boolean requiredAccounting,
            String specialTaxpayer,
            Boolean microEnterpriseRegime,
            String withholdingAgent,
            String environment,
            String webhookUrl,
            String webhookSecret,
            Integer rateLimitRpm,
            Integer rateLimitWriteRpm,
            Integer rateLimitReadRpm,
            String emailSenderName,
            String replyEmail,
            String status) {

    }

    public record TenantListResult(java.util.List<Tenant> items, long total) {

    }

    /**
     * Excepción de dominio para errores de gestión de tenants.
     */
    public static class TenantException extends RuntimeException {

        private final String code;
        private final int httpStatus;

        public TenantException(String code, String message, int httpStatus) {
            super(message);
            this.code = code;
            this.httpStatus = httpStatus;
        }

        public String code() {
            return code;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }
}
