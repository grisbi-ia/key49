package auracore.key49.core.model.enums;

import java.util.Arrays;

/**
 * Tipos de plan comercial SaaS con cuotas de documentos por defecto.
 */
public enum PlanType {

    DEMO("demo", 25),
    STARTER("starter", 100),
    BUSINESS("business", 500),
    ENTERPRISE("enterprise", 5000);

    private final String code;
    private final int defaultQuota;

    PlanType(String code, int defaultQuota) {
        this.code = code;
        this.defaultQuota = defaultQuota;
    }

    public String code() {
        return code;
    }

    public int defaultQuota() {
        return defaultQuota;
    }

    public static PlanType fromCode(String code) {
        return Arrays.stream(values())
                .filter(p -> p.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan type: " + code));
    }
}
