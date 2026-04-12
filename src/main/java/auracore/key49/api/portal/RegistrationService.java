package auracore.key49.api.portal;

import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.service.PasswordHasher;
import auracore.key49.core.validation.SriValidator;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Servicio de registro de nuevos tenants via wizard del portal.
 *
 * <p>
 * Almacena datos del paso 1 (empresa) en Redis con TTL de 30 minutos. No crea
 * el tenant hasta completar todos los pasos del wizard.</p>
 */
@ApplicationScoped
public class RegistrationService {

    static final String REG_PREFIX = "portal:registration:";
    private static final Duration REG_TTL = Duration.ofMinutes(30);
    private static final int MIN_PASSWORD_LENGTH = 8;

    @Inject
    Logger log;

    @Inject
    RedisDataSource redisDS;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    PasswordHasher passwordHasher;

    /**
     * Resultado de validación del paso 1.
     */
    public record Step1Result(boolean success, String error, String registrationId) {

    }

    /**
     * Resultado de verificación de RUC.
     */
    public record RucCheckResult(boolean valid, boolean registered, String message) {

    }

    /**
     * Verifica un RUC: formato módulo 11 y si ya está registrado.
     */
    public RucCheckResult verifyRuc(String ruc) {
        if (ruc == null || ruc.isBlank()) {
            return new RucCheckResult(false, false, "El RUC es obligatorio");
        }

        String trimmed = ruc.strip();
        if (!SriValidator.isValidRuc(trimmed)) {
            return new RucCheckResult(false, false, "RUC inválido (debe ser 13 dígitos con módulo 11 válido)");
        }

        var existing = tenantRepository.findByRuc(trimmed);
        if (existing != null) {
            return new RucCheckResult(true, true,
                    "Este RUC ya se encuentra registrado. Si olvidó su contraseña, puede recuperarla.");
        }

        return new RucCheckResult(true, false, "RUC válido");
    }

    /**
     * Valida y almacena los datos del paso 1 en Redis.
     *
     * @return resultado con registrationId si exitoso, o mensaje de error
     */
    public Step1Result saveStep1(String ruc, String legalName, String email,
            String password, String confirmPassword) {
        // Validar RUC
        var rucCheck = verifyRuc(ruc);
        if (!rucCheck.valid()) {
            return new Step1Result(false, rucCheck.message(), null);
        }
        if (rucCheck.registered()) {
            return new Step1Result(false, rucCheck.message(), null);
        }

        String trimmedRuc = ruc.strip();
        String trimmedName = legalName != null ? legalName.strip() : "";
        String trimmedEmail = email != null ? email.strip().toLowerCase() : "";

        // Validar razón social
        if (trimmedName.isEmpty() || trimmedName.length() < 3) {
            return new Step1Result(false, "La razón social debe tener al menos 3 caracteres", null);
        }

        // Validar email
        if (trimmedEmail.isEmpty() || !trimmedEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            return new Step1Result(false, "Ingrese un email válido", null);
        }

        // Verificar email no duplicado
        var existingByEmail = tenantRepository.findByEmail(trimmedEmail);
        if (existingByEmail != null) {
            return new Step1Result(false, "Este email ya se encuentra registrado", null);
        }

        // Validar contraseña
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            return new Step1Result(false,
                    "La contraseña debe tener al menos " + MIN_PASSWORD_LENGTH + " caracteres", null);
        }
        if (!password.equals(confirmPassword)) {
            return new Step1Result(false, "Las contraseñas no coinciden", null);
        }

        // Guardar en Redis
        String registrationId = UUID.randomUUID().toString();
        String key = REG_PREFIX + registrationId;
        String hashedPassword = passwordHasher.hash(password);

        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        hash.hset(key, Map.of(
                "ruc", trimmedRuc,
                "legal_name", trimmedName,
                "email", trimmedEmail,
                "password_hash", hashedPassword,
                "step", "1"
        ));

        KeyCommands<String> keys = redisDS.key(String.class);
        keys.pexpire(key, REG_TTL.toMillis());

        log.infof("Registration step 1 saved | registrationId=%s ruc=%s", registrationId, trimmedRuc);
        return new Step1Result(true, null, registrationId);
    }

    /**
     * Obtiene los datos de una sesión de registro desde Redis.
     *
     * @return mapa con los datos o null si no existe/expiró
     */
    public Map<String, String> getRegistrationData(String registrationId) {
        if (registrationId == null || registrationId.isBlank()) {
            return null;
        }

        String key = REG_PREFIX + registrationId;
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        var data = hash.hgetall(key);

        if (data == null || data.isEmpty()) {
            return null;
        }
        return data;
    }
}
