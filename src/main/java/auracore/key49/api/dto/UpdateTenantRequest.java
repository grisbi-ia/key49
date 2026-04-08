package auracore.key49.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request para actualizar un tenant existente. Campos nulos no se actualizan.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateTenantRequest(
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
