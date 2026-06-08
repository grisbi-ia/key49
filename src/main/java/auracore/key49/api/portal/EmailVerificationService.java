package auracore.key49.api.portal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.service.TenantCacheService;
import auracore.key49.notify.email.PlatformEmailService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Servicio de verificación de email para autoregistro.
 *
 * <p>
 * Genera un token UUID, lo almacena en Redis con TTL de 24 horas y envía un
 * email con enlace de verificación. Al verificar, activa el tenant
 * ({@code email_verified = true}, {@code status = 'active'}).</p>
 *
 * <p>
 * Rate limiting: máximo 3 solicitudes de reenvío por email por hora.</p>
 */
@ApplicationScoped
public class EmailVerificationService {

    static final String VERIFY_PREFIX = "portal:verify-email:";
    static final String RATE_PREFIX = "portal:verify-rate:";
    static final Duration VERIFY_TTL = Duration.ofHours(24);
    private static final Duration RATE_WINDOW = Duration.ofHours(1);
    private static final int MAX_REQUESTS_PER_HOUR = 3;

    @Inject
    Logger log;

    @Inject
    RedisDataSource redisDS;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    TenantCacheService tenantCacheService;

    @Inject
    PlatformEmailService platformEmailService;

    @Location("portal/email-verification")
    Template emailVerificationTemplate;

    @ConfigProperty(name = "key49.portal.base-url", defaultValue = "http://localhost:8080")
    String portalBaseUrl;

    public record SendResult(boolean success, String error, String token) {

    }

    public record VerifyResult(boolean success, String error) {

    }

    /**
     * Genera token de verificación, lo almacena en Redis y envía email.
     *
     * @param tenantId ID del tenant recién creado
     * @param email dirección de email a verificar
     * @param legalName razón social (para el email)
     * @return resultado con el token generado si exitoso
     */
    public SendResult sendVerificationEmail(UUID tenantId, String email, String legalName) {
        if (tenantId == null || email == null || email.isBlank()) {
            return new SendResult(false, "Datos insuficientes para enviar verificación", null);
        }

        String trimmedEmail = email.strip().toLowerCase();

        // Rate limiting
        if (isRateLimited(trimmedEmail)) {
            return new SendResult(false,
                    "Ha excedido el límite de solicitudes de verificación. Intente en una hora.", null);
        }
        incrementRateCounter(trimmedEmail);

        // Generar token
        String token = UUID.randomUUID().toString();
        String key = VERIFY_PREFIX + token;

        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        hash.hset(key, Map.of(
                "email", trimmedEmail,
                "tenant_id", tenantId.toString(),
                "created_at", Instant.now().toString()));

        KeyCommands<String> keys = redisDS.key(String.class);
        keys.pexpire(key, VERIFY_TTL.toMillis());

        // Enviar email
        try {
            sendEmail(trimmedEmail, legalName, token);
        } catch (Exception e) {
            log.errorf(e, "Failed to send verification email | email=%s tenant=%s", trimmedEmail, tenantId);
            // No borramos el token — el usuario puede pedir reenvío
        }

        log.infof("Verification email sent | tenant=%s email=%s token=%s...",
                tenantId, trimmedEmail, token.substring(0, 8));
        return new SendResult(true, null, token);
    }

    /**
     * Verifica el token: activa el tenant y marca
     * {@code email_verified = true}.
     *
     * @param token token UUID de la URL
     * @return resultado de la verificación
     */
    @Transactional
    public VerifyResult verify(String token) {
        if (token == null || token.isBlank()) {
            return new VerifyResult(false, "Token inválido");
        }

        String key = VERIFY_PREFIX + token.strip();
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        var data = hash.hgetall(key);

        if (data == null || data.isEmpty()) {
            return new VerifyResult(false,
                    "El enlace de verificación ha expirado o es inválido. "
                    + "Solicite un nuevo enlace desde la página de inicio de sesión.");
        }

        String tenantIdStr = data.get("tenant_id");
        String email = data.get("email");

        // Consumir token inmediatamente (incluso si datos incompletos)
        KeyCommands<String> keys = redisDS.key(String.class);
        keys.del(key);

        if (tenantIdStr == null || email == null) {
            return new VerifyResult(false, "Datos de verificación incompletos");
        }

        // Activar tenant
        UUID tenantId = UUID.fromString(tenantIdStr);
        Tenant tenant = tenantRepository.findById(tenantId);

        if (tenant == null) {
            return new VerifyResult(false, "La cuenta no fue encontrada");
        }

        if (tenant.emailVerified) {
            return new VerifyResult(true, null); // Ya verificado, idempotente
        }

        if (!"pending".equals(tenant.status) && !"pending_approval".equals(tenant.status)) {
            return new VerifyResult(false,
                    "La cuenta no se encuentra en estado pendiente de verificación (estado actual: " + tenant.status + ")");
        }

        if ("pending_approval".equals(tenant.status)) {
            // Ya verificado previamente — idempotente
            return new VerifyResult(true, null);
        }

        tenant.emailVerified = true;
        tenant.status = "pending_approval";
        tenant.updatedAt = Instant.now();

        // Invalidar caché Redis del tenant
        tenantCacheService.invalidate(tenant.id, tenant.schemaName);

        log.infof("Email verified, tenant activated | tenant=%s email=%s", tenantId, email);
        return new VerifyResult(true, null);
    }

    private boolean isRateLimited(String email) {
        String rateKey = RATE_PREFIX + email;
        ValueCommands<String, String> values = redisDS.value(String.class, String.class);
        String count = values.get(rateKey);
        if (count == null) {
            return false;
        }
        return Integer.parseInt(count) >= MAX_REQUESTS_PER_HOUR;
    }

    private void incrementRateCounter(String email) {
        String rateKey = RATE_PREFIX + email;
        ValueCommands<String, String> values = redisDS.value(String.class, String.class);
        String current = values.get(rateKey);

        if (current == null) {
            values.set(rateKey, "1");
            KeyCommands<String> keys = redisDS.key(String.class);
            keys.pexpire(rateKey, RATE_WINDOW.toMillis());
        } else {
            int newCount = Integer.parseInt(current) + 1;
            KeyCommands<String> keys = redisDS.key(String.class);
            long ttl = keys.pttl(rateKey);
            values.set(rateKey, String.valueOf(newCount));
            if (ttl > 0) {
                keys.pexpire(rateKey, ttl);
            } else {
                keys.pexpire(rateKey, RATE_WINDOW.toMillis());
            }
        }
    }

    private void sendEmail(String email, String legalName, String token) {
        String verifyUrl = portalBaseUrl + "/portal/verify?token=" + token;

        var htmlBody = emailVerificationTemplate
                .data("legalName", legalName != null ? legalName : "Usuario")
                .data("verifyUrl", verifyUrl)
                .data("expirationHours", 24)
                .render();

        platformEmailService.sendHtml(email, "Key49 — Verifique su dirección de email", htmlBody);
    }
}
