package auracore.key49.api.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import auracore.key49.core.model.Tenant;

/**
 * Respuesta con datos de un tenant.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantResponse(
        UUID id,
        String ruc,
        String legalName,
        String tradeName,
        String mainAddress,
        boolean requiredAccounting,
        String specialTaxpayer,
        boolean microEnterpriseRegime,
        String withholdingAgent,
        String environment,
        String schemaName,
        String status,
        CertificateSummary certificate,
        String webhookUrl,
        Integer rateLimitRpm,
        Integer rateLimitWriteRpm,
        Integer rateLimitReadRpm,
        String emailSenderName,
        String replyEmail,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * Resumen del certificado configurado en el tenant.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CertificateSummary(
            String subject,
            String serial,
            Instant expiresAt,
            boolean configured,
            boolean valid,
            boolean pendingRotation) {

        public CertificateSummary(String subject, String serial,
                Instant expiresAt, boolean configured, boolean valid) {
            this(subject, serial, expiresAt, configured, valid, false);
        }
    }

    /**
     * Convierte un Tenant entity a TenantResponse (lista/resumen).
     */
    public static TenantResponse fromEntity(Tenant t) {
        CertificateSummary cert;
        boolean pendingRotation = t.pendingCertificateP12 != null;
        if (t.certificateP12 != null && t.certificateP12.length > 0) {
            boolean valid = t.certificateExpiration != null && t.certificateExpiration.isAfter(Instant.now());
            cert = new CertificateSummary(
                    t.certificateSubject, t.certificateSerial,
                    t.certificateExpiration, true, valid, pendingRotation);
        } else {
            cert = new CertificateSummary(null, null, null, false, false, pendingRotation);
        }

        return new TenantResponse(
                t.id, t.ruc, t.legalName, t.tradeName, t.mainAddress,
                t.requiredAccounting, t.specialTaxpayer, t.microEnterpriseRegime,
                t.withholdingAgent, t.environment, t.schemaName, t.status,
                cert, t.webhookUrl, t.rateLimitRpm,
                t.rateLimitWriteRpm, t.rateLimitReadRpm,
                t.emailSenderName, t.replyEmail,
                t.createdAt, t.updatedAt);
    }
}
