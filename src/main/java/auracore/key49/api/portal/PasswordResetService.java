package auracore.key49.api.portal;

import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.service.PasswordHasher;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Servicio de recuperación de contraseña del portal.
 *
 * <p>
 * Genera un token UUID, lo almacena en Redis con TTL de 30 minutos, envía email
 * con enlace de restablecimiento, y actualiza la contraseña en la tabla
 * {@code public.tenants} tras validar el token.</p>
 *
 * <p>
 * Rate limiting: máximo 3 solicitudes por email por hora, controlado con
 * contadores en Redis.</p>
 */
@ApplicationScoped
public class PasswordResetService {

    static final String RESET_PREFIX = "portal:reset:";
    static final String RATE_PREFIX = "portal:reset-rate:";
    private static final Duration RESET_TTL = Duration.ofMinutes(30);
    private static final Duration RATE_WINDOW = Duration.ofHours(1);
    private static final int MAX_REQUESTS_PER_HOUR = 3;
    private static final int MIN_PASSWORD_LENGTH = 8;

    @Inject
    Logger log;

    @Inject
    RedisDataSource redisDS;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    PasswordHasher passwordHasher;

    @Inject
    ReactiveMailer reactiveMailer;

    @Location("portal/password-reset-email")
    Template passwordResetEmailTemplate;

    @ConfigProperty(name = "key49.portal.base-url", defaultValue = "http://localhost:8080")
    String portalBaseUrl;

    @ConfigProperty(name = "key49.email.from", defaultValue = "facturacion@key49.ec")
    String fromAddress;

    @ConfigProperty(name = "key49.email.send-timeout-seconds", defaultValue = "120")
    int sendTimeoutSeconds;

    /**
     * Resultado de la solicitud de recuperación.
     */
    public record RequestResult(boolean success, String error) {

    }

    /**
     * Resultado de validación de token.
     */
    public record TokenValidation(boolean valid, String error, String email) {

    }

    /**
     * Resultado del restablecimiento de contraseña.
     */
    public record ResetResult(boolean success, String error) {

    }

    /**
     * Solicita recuperación de contraseña: valida email, aplica rate limit,
     * genera token, envía email.
     *
     * <p>
     * Siempre retorna éxito al usuario para no revelar si el email existe.</p>
     *
     * @param email dirección de email del tenant
     * @return resultado (siempre success=true si no hay error de rate limit)
     */
    public RequestResult requestReset(String email) {
        if (email == null || email.isBlank()) {
            return new RequestResult(false, "El email es obligatorio");
        }

        String trimmedEmail = email.strip().toLowerCase();

        if (!trimmedEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            return new RequestResult(false, "Ingrese un email válido");
        }

        // Rate limiting: máximo 3 solicitudes por email por hora
        if (isRateLimited(trimmedEmail)) {
            return new RequestResult(false,
                    "Ha excedido el límite de solicitudes. Intente nuevamente en una hora.");
        }

        // Incrementar contador de rate limit
        incrementRateCounter(trimmedEmail);

        // Buscar tenant (no revelar si existe o no)
        Tenant tenant = tenantRepository.findByEmail(trimmedEmail);
        if (tenant == null || !"active".equals(tenant.status)) {
            log.infof("Password reset requested for non-existent email | email=%s", trimmedEmail);
            // Retornar éxito para no revelar si el email está registrado
            return new RequestResult(true, null);
        }

        // Generar token
        String token = UUID.randomUUID().toString();
        String key = RESET_PREFIX + token;

        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        hash.hset(key, Map.of(
                "email", trimmedEmail,
                "tenant_id", tenant.id.toString(),
                "created_at", Instant.now().toString()
        ));

        KeyCommands<String> keys = redisDS.key(String.class);
        keys.pexpire(key, RESET_TTL.toMillis());

        // Enviar email
        try {
            sendResetEmail(trimmedEmail, tenant.legalName, token);
        } catch (Exception e) {
            log.errorf(e, "Failed to send password reset email | email=%s", trimmedEmail);
            // No eliminar el token — el usuario puede reintentar
        }

        log.infof("Password reset token generated | email=%s token=%s...",
                trimmedEmail, token.substring(0, 8));
        return new RequestResult(true, null);
    }

    /**
     * Valida un token de recuperación sin consumirlo.
     *
     * @param token token UUID de la URL
     * @return resultado con email si válido
     */
    public TokenValidation validateToken(String token) {
        if (token == null || token.isBlank()) {
            return new TokenValidation(false, "Token inválido", null);
        }

        String key = RESET_PREFIX + token.strip();
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        var data = hash.hgetall(key);

        if (data == null || data.isEmpty()) {
            return new TokenValidation(false,
                    "El enlace ha expirado o es inválido. Solicite uno nuevo.", null);
        }

        return new TokenValidation(true, null, data.get("email"));
    }

    /**
     * Restablece la contraseña: valida token, actualiza hash en BD, invalida
     * token en Redis.
     *
     * @param token token UUID de la URL
     * @param newPassword nueva contraseña
     * @param confirmPassword confirmación de la nueva contraseña
     * @return resultado de la operación
     */
    @Transactional
    public ResetResult resetPassword(String token, String newPassword, String confirmPassword) {
        if (token == null || token.isBlank()) {
            return new ResetResult(false, "Token inválido");
        }

        // Validar contraseña
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            return new ResetResult(false,
                    "La contraseña debe tener al menos " + MIN_PASSWORD_LENGTH + " caracteres");
        }
        if (!newPassword.equals(confirmPassword)) {
            return new ResetResult(false, "Las contraseñas no coinciden");
        }

        // Leer y consumir token atómicamente
        String key = RESET_PREFIX + token.strip();
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        var data = hash.hgetall(key);

        if (data == null || data.isEmpty()) {
            return new ResetResult(false,
                    "El enlace ha expirado o es inválido. Solicite uno nuevo.");
        }

        String email = data.get("email");
        String tenantIdStr = data.get("tenant_id");

        if (email == null || tenantIdStr == null) {
            return new ResetResult(false, "Datos de recuperación incompletos. Solicite un nuevo enlace.");
        }

        // Invalidar token inmediatamente (antes de actualizar BD)
        KeyCommands<String> keys = redisDS.key(String.class);
        keys.del(key);

        // Actualizar contraseña en BD
        UUID tenantId = UUID.fromString(tenantIdStr);
        Tenant tenant = tenantRepository.findById(tenantId);
        if (tenant == null || !"active".equals(tenant.status)) {
            return new ResetResult(false, "La cuenta no se encuentra activa.");
        }

        tenant.portalPasswordHash = passwordHasher.hash(newPassword);
        tenant.updatedAt = Instant.now();

        log.infof("Password reset completed | tenantId=%s email=%s", tenantId, email);
        return new ResetResult(true, null);
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
            // INCR preserva TTL, pero setrange no — usar set with keepttl not available,
            // so we read TTL, set new value, re-apply TTL
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

    private void sendResetEmail(String email, String legalName, String token) {
        String resetUrl = portalBaseUrl + "/portal/reset-password?token=" + token;

        var htmlBody = passwordResetEmailTemplate
                .data("legalName", legalName != null ? legalName : "Usuario")
                .data("resetUrl", resetUrl)
                .data("expirationMinutes", 30)
                .render();

        var mail = Mail.withHtml(email,
                "Key49 — Recuperación de contraseña",
                htmlBody);
        mail.setFrom("Key49 <" + fromAddress + ">");

        reactiveMailer.send(mail)
                .await().atMost(Duration.ofSeconds(sendTimeoutSeconds));
    }
}
