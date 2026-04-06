package auracore.key49.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request para que un tenant actualice su propio perfil. Campos nulos no se actualizan.
 * Subconjunto de UpdateTenantRequest — excluye campos administrativos (status, rateLimitRpm).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateProfileRequest(
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
        String emailSenderName,
        String replyEmail) {}
