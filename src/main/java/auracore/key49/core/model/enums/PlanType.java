package auracore.key49.core.model.enums;

import java.util.Arrays;

/**
 * Tipos de plan comercial SaaS con cuotas de documentos y límites de rate
 * limiting.
 */
public enum PlanType {

    DEMO("demo", 25, 10, 30),
    STARTER("starter", 100, 30, 100),
    BUSINESS("business", 500, 60, 200),
    ENTERPRISE("enterprise", 5000, 200, 600);

    private final String code;
    private final int defaultQuota;
    private final int writeRpm;
    private final int readRpm;

    PlanType(String code, int defaultQuota, int writeRpm, int readRpm) {
        this.code = code;
        this.defaultQuota = defaultQuota;
        this.writeRpm = writeRpm;
        this.readRpm = readRpm;
    }

    public String code() {
        return code;
    }

    public int defaultQuota() {
        return defaultQuota;
    }

    public int writeRpm() {
        return writeRpm;
    }

    public int readRpm() {
        return readRpm;
    }

    /**
     * RPM general (suma de write + read, para compatibilidad con campo legacy
     * {@code rate_limit_rpm}).
     */
    public int totalRpm() {
        return writeRpm + readRpm;
    }

    public static PlanType fromCode(String code) {
        return Arrays.stream(values())
                .filter(p -> p.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown plan type: " + code));
    }
}
