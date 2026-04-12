package auracore.key49.api.portal;

import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.service.PasswordHasher;
import auracore.key49.core.validation.SriValidator;
import auracore.key49.notify.webhook.WebhookUrlValidator;
import auracore.key49.signer.CertificateEncryptor;
import auracore.key49.signer.CertificateMetadataExtractor;
import auracore.key49.signer.CertificateMetadataExtractor.CertificateMetadata;
import auracore.key49.signer.SigningException;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Base64;
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
    private static final int MAX_P12_SIZE = 50 * 1024; // 50 KB

    @Inject
    Logger log;

    @Inject
    RedisDataSource redisDS;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    PasswordHasher passwordHasher;

    @ConfigProperty(name = "key49.master-key")
    String masterKeyBase64;

    /**
     * Resultado de validación del paso 1.
     */
    public record Step1Result(boolean success, String error, String registrationId) {

    }

    /**
     * Resultado de validación del paso 2.
     */
    public record Step2Result(boolean success, String error, CertificateMetadata metadata) {

    }

    /**
     * Resultado de validación del paso 3.
     */
    public record Step3Result(boolean success, String error) {

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
     * Valida el certificado .p12, extrae metadata, cifra con AES-256-GCM y
     * actualiza la sesión de registro en Redis.
     *
     * @param registrationId ID de la sesión de registro (cookie KEY49_REG)
     * @param p12Bytes contenido binario del archivo .p12
     * @param certPassword contraseña del certificado
     * @param environment ambiente: "TEST" o "PRODUCTION"
     * @return resultado con metadata si exitoso, o mensaje de error
     */
    public Step2Result saveStep2(String registrationId, byte[] p12Bytes,
            String certPassword, String environment) {
        // Verificar que existe sesión de registro en Redis
        var regData = getRegistrationData(registrationId);
        if (regData == null) {
            return new Step2Result(false, "Sesión de registro expirada. Inicie el registro nuevamente.", null);
        }

        // Validar archivo
        if (p12Bytes == null || p12Bytes.length == 0) {
            return new Step2Result(false, "Debe seleccionar un archivo de certificado .p12", null);
        }
        if (p12Bytes.length > MAX_P12_SIZE) {
            return new Step2Result(false, "El archivo excede el tamaño máximo de 50 KB", null);
        }

        // Validar magic bytes — PKCS#12 es DER-encoded, empieza con SEQUENCE tag 0x30
        if (p12Bytes[0] != 0x30) {
            return new Step2Result(false, "El archivo no es un certificado PKCS#12 (.p12) válido", null);
        }

        // Validar contraseña del certificado
        if (certPassword == null || certPassword.isBlank()) {
            return new Step2Result(false, "La contraseña del certificado es obligatoria", null);
        }

        // Validar ambiente
        if (!"TEST".equals(environment) && !"PRODUCTION".equals(environment)) {
            return new Step2Result(false, "Seleccione un ambiente válido (TEST o PRODUCCIÓN)", null);
        }

        // Intentar cargar el .p12 para validar contraseña y extraer metadata
        CertificateMetadata metadata;
        try {
            metadata = CertificateMetadataExtractor.extract(p12Bytes, certPassword.toCharArray());
        } catch (SigningException e) {
            log.debugf("Registration step 2: invalid certificate | registrationId=%s error=%s",
                    registrationId, e.getMessage());
            return new Step2Result(false,
                    "No se pudo leer el certificado. Verifique que el archivo y la contraseña sean correctos.", null);
        }

        // Validar que el certificado no esté expirado
        if (!metadata.valid()) {
            return new Step2Result(false,
                    "El certificado está expirado (venció el "
                    + java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")
                            .withZone(java.time.ZoneId.of("America/Guayaquil"))
                            .format(metadata.expiresAt()) + ")", null);
        }

        // Cifrar el .p12 y la contraseña con AES-256-GCM
        byte[] masterKey = CertificateEncryptor.decodeMasterKey(masterKeyBase64);
        byte[] encryptedP12 = CertificateEncryptor.encrypt(p12Bytes, masterKey);
        byte[] encryptedPassword = CertificateEncryptor.encryptPassword(certPassword.toCharArray(), masterKey);

        // Guardar en Redis como Base64 (Redis strings)
        String key = REG_PREFIX + registrationId;
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        hash.hset(key, Map.of(
                "cert_p12_enc", Base64.getEncoder().encodeToString(encryptedP12),
                "cert_password_enc", Base64.getEncoder().encodeToString(encryptedPassword),
                "cert_subject", metadata.subject(),
                "cert_serial", metadata.serial(),
                "cert_expires_at", metadata.expiresAt().toString(),
                "cert_issuer", metadata.issuer(),
                "environment", environment,
                "step", "2"
        ));

        // Renovar TTL
        KeyCommands<String> keys = redisDS.key(String.class);
        keys.pexpire(key, REG_TTL.toMillis());

        log.infof("Registration step 2 saved | registrationId=%s subject=%s environment=%s",
                registrationId, metadata.subject(), environment);
        return new Step2Result(true, null, metadata);
    }

    /**
     * Valida y almacena la configuración opcional de SMTP y webhook URL en la
     * sesión de registro en Redis (paso 3).
     *
     * <p>
     * Todos los campos son opcionales. Si se proporciona SMTP, host y puerto
     * son obligatorios. La contraseña SMTP se cifra con AES-256-GCM. El webhook
     * URL se valida contra SSRF.</p>
     *
     * @param registrationId ID de la sesión de registro
     * @param smtpHost host SMTP (opcional)
     * @param smtpPort puerto SMTP (opcional)
     * @param smtpUser usuario SMTP (opcional)
     * @param smtpPassword contraseña SMTP (opcional)
     * @param smtpFromEmail email remitente (opcional)
     * @param webhookUrl URL de webhook (opcional)
     * @return resultado indicando éxito o error
     */
    public Step3Result saveStep3(String registrationId, String smtpHost, String smtpPort,
            String smtpUser, String smtpPassword, String smtpFromEmail, String webhookUrl) {
        // Verificar sesión de registro
        var regData = getRegistrationData(registrationId);
        if (regData == null) {
            return new Step3Result(false, "Sesión de registro expirada. Inicie el registro nuevamente.");
        }

        var trimmedHost = smtpHost != null ? smtpHost.strip() : "";
        var trimmedPort = smtpPort != null ? smtpPort.strip() : "";
        var trimmedUser = smtpUser != null ? smtpUser.strip() : "";
        var trimmedPassword = smtpPassword != null ? smtpPassword : "";
        var trimmedFrom = smtpFromEmail != null ? smtpFromEmail.strip().toLowerCase() : "";
        var trimmedWebhook = webhookUrl != null ? webhookUrl.strip() : "";

        boolean hasSmtp = !trimmedHost.isEmpty() || !trimmedPort.isEmpty()
                || !trimmedUser.isEmpty() || !trimmedPassword.isEmpty() || !trimmedFrom.isEmpty();

        var fields = new java.util.HashMap<String, String>();

        if (hasSmtp) {
            // Si se proporcionó algún campo SMTP, host y puerto son obligatorios
            if (trimmedHost.isEmpty()) {
                return new Step3Result(false, "El host SMTP es obligatorio si configura email propio");
            }
            if (trimmedPort.isEmpty()) {
                return new Step3Result(false, "El puerto SMTP es obligatorio si configura email propio");
            }
            int port;
            try {
                port = Integer.parseInt(trimmedPort);
            } catch (NumberFormatException e) {
                return new Step3Result(false, "El puerto SMTP debe ser un número válido");
            }
            if (port < 1 || port > 65535) {
                return new Step3Result(false, "El puerto SMTP debe estar entre 1 y 65535");
            }

            // Validar email remitente si se proporcionó
            if (!trimmedFrom.isEmpty()
                    && !trimmedFrom.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                return new Step3Result(false, "El email remitente no tiene un formato válido");
            }

            fields.put("smtp_host", trimmedHost);
            fields.put("smtp_port", String.valueOf(port));
            if (!trimmedUser.isEmpty()) {
                fields.put("smtp_user", trimmedUser);
            }
            if (!trimmedPassword.isEmpty()) {
                byte[] masterKey = CertificateEncryptor.decodeMasterKey(masterKeyBase64);
                byte[] encryptedPassword = CertificateEncryptor.encryptPassword(
                        trimmedPassword.toCharArray(), masterKey);
                fields.put("smtp_password_enc", Base64.getEncoder().encodeToString(encryptedPassword));
            }
            if (!trimmedFrom.isEmpty()) {
                fields.put("smtp_from_email", trimmedFrom);
            }
            fields.put("smtp_enabled", "true");
        }

        // Validar webhook URL si se proporcionó
        if (!trimmedWebhook.isEmpty()) {
            try {
                WebhookUrlValidator.validate(trimmedWebhook);
            } catch (IllegalArgumentException e) {
                return new Step3Result(false, "URL de webhook inválida: " + e.getMessage());
            }
            fields.put("webhook_url", trimmedWebhook);
        }

        // Guardar en Redis
        String key = REG_PREFIX + registrationId;
        HashCommands<String, String, String> hash = redisDS.hash(String.class, String.class, String.class);
        fields.put("step", "3");
        hash.hset(key, fields);

        // Renovar TTL
        KeyCommands<String> keys = redisDS.key(String.class);
        keys.pexpire(key, REG_TTL.toMillis());

        log.infof("Registration step 3 saved | registrationId=%s smtp=%s webhook=%s",
                registrationId, hasSmtp, !trimmedWebhook.isEmpty());
        return new Step3Result(true, null);
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
