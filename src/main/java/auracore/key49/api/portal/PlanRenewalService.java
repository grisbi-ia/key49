package auracore.key49.api.portal;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import auracore.key49.core.model.PlanRenewal;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.PlanRenewalRepository;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.notify.plan.PlanAlertService;
import auracore.key49.storage.ObjectStorageService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Servicio de solicitud de renovación de plan desde el portal del tenant.
 * Gestiona la creación de solicitudes, subida de comprobante de pago a MinIO, y
 * disparo de webhook {@code plan.renewal_requested}.
 */
@ApplicationScoped
public class PlanRenewalService {

    private static final Logger log = Logger.getLogger(PlanRenewalService.class);

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "application/pdf");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "pdf");

    @Inject
    TenantRepository tenantRepository;

    @Inject
    PlanRenewalRepository planRenewalRepository;

    @Inject
    ObjectStorageService storageService;

    @Inject
    PlanAlertService planAlertService;

    /**
     * Planes disponibles con precios y cuotas (placeholder hasta T-107).
     */
    public record PlanInfo(String code, String name, int documentQuota, BigDecimal monthlyPrice) {

    }

    public static final List<PlanInfo> AVAILABLE_PLANS = List.of(
            new PlanInfo("starter", "Starter", 100, new BigDecimal("9.99")),
            new PlanInfo("business", "Business", 500, new BigDecimal("29.99")),
            new PlanInfo("enterprise", "Enterprise", 10000, new BigDecimal("99.99")));

    /**
     * Resultado de la solicitud de renovación.
     */
    public record RenewalResult(boolean success, String error, PlanRenewal renewal) {

    }

    /**
     * Información del plan actual del tenant.
     */
    public record CurrentPlan(String planType, int documentQuota, int documentsUsed,
            Instant planStartsAt, Instant planExpiresAt, int usagePercent) {

    }

    /**
     * Obtiene información del plan actual del tenant.
     */
    public CurrentPlan getCurrentPlan(UUID tenantId) {
        var tenant = tenantRepository.findById(tenantId);
        if (tenant == null) {
            return null;
        }
        int usagePercent = tenant.documentQuota > 0
                ? (tenant.documentsUsed * 100 / tenant.documentQuota)
                : 0;
        return new CurrentPlan(
                tenant.planType, tenant.documentQuota, tenant.documentsUsed,
                tenant.planStartsAt, tenant.planExpiresAt, usagePercent);
    }

    /**
     * Verifica si el tenant tiene una solicitud de renovación pendiente.
     */
    public boolean hasPendingRenewal(UUID tenantId) {
        return !planRenewalRepository.findPendingByTenantId(tenantId).isEmpty();
    }

    /**
     * Historial de solicitudes de renovación del tenant.
     */
    public List<PlanRenewal> getRenewalHistory(UUID tenantId) {
        return planRenewalRepository.findByTenantId(tenantId);
    }

    /**
     * Crea una solicitud de renovación con comprobante de pago.
     *
     * @param tenantId ID del tenant
     * @param requestedPlan tipo de plan solicitado
     * @param paymentProof archivo de comprobante de pago
     * @param notes observaciones del tenant
     * @return resultado de la operación
     */
    @Transactional
    public RenewalResult requestRenewal(UUID tenantId, String requestedPlan,
            FileUpload paymentProof, String notes) {

        // Validar plan solicitado
        var planInfo = AVAILABLE_PLANS.stream()
                .filter(p -> p.code().equals(requestedPlan))
                .findFirst()
                .orElse(null);
        if (planInfo == null) {
            return new RenewalResult(false, "Plan no válido", null);
        }

        // Verificar que no haya renovación pendiente
        if (hasPendingRenewal(tenantId)) {
            return new RenewalResult(false,
                    "Ya tiene una solicitud de renovación pendiente. Espere a que sea procesada.", null);
        }

        // Verificar tenant existe
        var tenant = tenantRepository.findById(tenantId);
        if (tenant == null) {
            return new RenewalResult(false, "Tenant no encontrado", null);
        }

        // Validar archivo de comprobante
        var fileError = validatePaymentProof(paymentProof);
        if (fileError != null) {
            return new RenewalResult(false, fileError, null);
        }

        // Leer bytes del archivo
        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(paymentProof.filePath());
        } catch (java.io.IOException e) {
            log.errorf(e, "Error reading payment proof file for tenant=%s", tenantId);
            return new RenewalResult(false, "Error al leer el archivo subido", null);
        }

        // Crear registro de renovación
        var renewal = new PlanRenewal();
        renewal.tenantId = tenantId;
        renewal.planType = planInfo.code();
        renewal.documentQuota = planInfo.documentQuota();
        renewal.amount = planInfo.monthlyPrice();
        renewal.notes = notes;
        renewal.createdAt = Instant.now();
        planRenewalRepository.persist(renewal);

        // Subir comprobante a MinIO
        var extension = getFileExtension(paymentProof.fileName());
        var objectPath = "plan-renewals/%s/%s.%s".formatted(tenantId, renewal.id, extension);
        try {
            storageService.storeRaw(objectPath, fileBytes, paymentProof.contentType());
            renewal.paymentProofPath = objectPath;
        } catch (Exception e) {
            log.errorf(e, "Error uploading payment proof to MinIO for tenant=%s renewal=%s",
                    tenantId, renewal.id);
            return new RenewalResult(false, "Error al almacenar el comprobante de pago", null);
        }

        log.infof("Plan renewal requested | tenant=%s plan=%s quota=%d amount=%s renewal=%s",
                tenantId, planInfo.code(), planInfo.documentQuota(), planInfo.monthlyPrice(), renewal.id);

        // Disparar webhook plan.renewal_requested
        fireRenewalWebhook(tenant, renewal);

        return new RenewalResult(true, null, renewal);
    }

    private String validatePaymentProof(FileUpload file) {
        if (file == null || file.filePath() == null) {
            return "Debe adjuntar un comprobante de pago";
        }
        if (file.size() > MAX_FILE_SIZE) {
            return "El archivo excede el tamaño máximo de 5 MB";
        }
        if (file.contentType() == null || !ALLOWED_CONTENT_TYPES.contains(file.contentType())) {
            return "Tipo de archivo no permitido. Use JPG, PNG o PDF";
        }
        var ext = getFileExtension(file.fileName());
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            return "Extensión de archivo no permitida. Use .jpg, .png o .pdf";
        }
        return null;
    }

    static String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    private void fireRenewalWebhook(Tenant tenant, PlanRenewal renewal) {
        if (tenant.webhookUrl == null || tenant.webhookUrl.isBlank()) {
            return;
        }
        try {
            var payload = """
                    {"event":"plan.renewal_requested","renewal_id":"%s",\
                    "tenant_id":"%s","legal_name":"%s",\
                    "current_plan":"%s","requested_plan":"%s",\
                    "document_quota":%d,"amount":"%s",\
                    "timestamp":"%s"}\
                    """.formatted(
                    renewal.id, tenant.id, escapeJson(tenant.legalName),
                    tenant.planType, renewal.planType,
                    renewal.documentQuota, renewal.amount,
                    Instant.now().toString());
            planAlertService.fireAlert("plan.renewal_requested",
                    tenant.webhookUrl, tenant.webhookSecret,
                    payload, tenant.replyEmail,
                    "Key49 — Solicitud de renovación de plan recibida",
                    renewalEmailBody(tenant, renewal));
            log.infof("plan.renewal_requested webhook fired | tenant=%s renewal=%s", tenant.id, renewal.id);
        } catch (Exception e) {
            log.errorf(e, "Failed to fire plan.renewal_requested webhook | tenant=%s", tenant.id);
        }
    }

    private String renewalEmailBody(Tenant tenant, PlanRenewal renewal) {
        return """
                Estimado/a contribuyente,

                Su solicitud de renovación de plan ha sido recibida y está pendiente de revisión.

                Razón Social: %s
                Plan solicitado: %s
                Documentos incluidos: %d
                Monto: $%s

                Le notificaremos cuando su solicitud sea procesada.

                Atentamente,
                Key49 - Facturación Electrónica
                """.formatted(tenant.legalName, renewal.planType,
                renewal.documentQuota, renewal.amount);
    }

    private static String escapeJson(String input) {
        if (input == null) {
            return "";
        }
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
