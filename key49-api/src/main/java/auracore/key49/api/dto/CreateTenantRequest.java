package auracore.key49.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request para crear un nuevo tenant.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateTenantRequest(
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
