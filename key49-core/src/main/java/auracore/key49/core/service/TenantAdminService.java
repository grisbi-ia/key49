package auracore.key49.core.service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.validation.SriValidator;
import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;

/**
 * Servicio de administración de tenants: CRUD en esquema público.
 */
@ApplicationScoped
public class TenantAdminService {

    @Inject
    TenantRepository tenantRepository;

    @Inject
    Mutiny.SessionFactory sessionFactory;

    // ── Crear tenant ──

    public Uni<Tenant> create(CreateTenantData data) {
        validateCreateData(data);

        return sessionFactory.withTransaction(session ->
                tenantRepository.findByRuc(data.ruc())
                        .chain(existing -> {
                            if (existing != null) {
                                return Uni.createFrom().failure(
                                        new TenantException("DUPLICATE_RUC",
                                                "A tenant with RUC " + data.ruc() + " already exists", 409));
                            }
                            return tenantRepository.findBySchemaName(data.schemaName());
                        })
                        .chain(existing -> {
                            if (existing != null) {
                                return Uni.createFrom().failure(
                                        new TenantException("DUPLICATE_SCHEMA",
                                                "A tenant with schema_name '" + data.schemaName() + "' already exists",
                                                409));
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
                            return tenantRepository.persist(tenant);
                        })
        );
    }

    // ── Obtener tenant por ID ──

    public Uni<Tenant> findById(UUID id) {
        return tenantRepository.findById(id)
                .onItem().ifNull().failWith(() ->
                        new TenantException("TENANT_NOT_FOUND",
                                "Tenant not found: " + id, 404));
    }

    // ── Listar tenants con paginación ──

    public Uni<TenantListResult> listAll(int page, int perPage, Optional<String> statusFilter) {
        int offset = (page - 1) * perPage;
        String query = statusFilter.map(s -> "status = ?1").orElse("1=1");
        Object[] params = statusFilter.map(s -> new Object[]{s}).orElse(new Object[]{});

        var countUni = statusFilter.isPresent()
                ? tenantRepository.count("status", statusFilter.get())
                : tenantRepository.count();

        var listUni = tenantRepository.find(query, params)
                .page(offset / perPage, perPage)
                .list();

        return Uni.combine().all().unis(countUni, listUni).asTuple()
                .map(tuple -> new TenantListResult(tuple.getItem2(), tuple.getItem1()));
    }

    // ── Actualizar tenant ──

    public Uni<Tenant> update(UUID id, UpdateTenantData data) {
        return sessionFactory.withTransaction(session ->
                tenantRepository.findById(id)
                        .onItem().ifNull().failWith(() ->
                                new TenantException("TENANT_NOT_FOUND",
                                        "Tenant not found: " + id, 404))
                        .map(tenant -> {
                            if (data.legalName() != null) tenant.legalName = data.legalName();
                            if (data.tradeName() != null) tenant.tradeName = data.tradeName();
                            if (data.mainAddress() != null) tenant.mainAddress = data.mainAddress();
                            if (data.requiredAccounting() != null)
                                tenant.requiredAccounting = data.requiredAccounting();
                            if (data.specialTaxpayer() != null) tenant.specialTaxpayer = data.specialTaxpayer();
                            if (data.microEnterpriseRegime() != null)
                                tenant.microEnterpriseRegime = data.microEnterpriseRegime();
                            if (data.withholdingAgent() != null) tenant.withholdingAgent = data.withholdingAgent();
                            if (data.environment() != null) tenant.environment = data.environment();
                            if (data.webhookUrl() != null) tenant.webhookUrl = data.webhookUrl();
                            if (data.webhookSecret() != null) tenant.webhookSecret = data.webhookSecret();
                            if (data.rateLimitRpm() != null) tenant.rateLimitRpm = data.rateLimitRpm();
                            if (data.emailSenderName() != null) tenant.emailSenderName = data.emailSenderName();
                            if (data.replyEmail() != null) tenant.replyEmail = data.replyEmail();
                            if (data.status() != null) tenant.status = data.status();
                            tenant.updatedAt = Instant.now();

                            Log.infof("Updated tenant | id=%s ruc=%s", id, tenant.ruc);
                            return tenant;
                        })
        );
    }

    // ── Subir certificado ──

    public Uni<Tenant> uploadCertificate(UUID id, byte[] encryptedP12, byte[] encryptedPassword,
                                         String subject, Instant expiration, String serial) {
        return sessionFactory.withTransaction(session ->
                tenantRepository.findById(id)
                        .onItem().ifNull().failWith(() ->
                                new TenantException("TENANT_NOT_FOUND",
                                        "Tenant not found: " + id, 404))
                        .map(tenant -> {
                            tenant.certificateP12 = encryptedP12;
                            tenant.certificatePasswordEnc = encryptedPassword;
                            tenant.certificateSubject = subject;
                            tenant.certificateExpiration = expiration;
                            tenant.certificateSerial = serial;
                            tenant.updatedAt = Instant.now();

                            Log.infof("Certificate uploaded | tenantId=%s subject=%s expires=%s",
                                    id, subject, expiration);
                            return tenant;
                        })
        );
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
