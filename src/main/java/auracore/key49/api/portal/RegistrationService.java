package auracore.key49.api.portal;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import auracore.key49.core.model.enums.PlanType;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.service.ApiKeyManagementService;
import auracore.key49.core.service.PasswordHasher;
import auracore.key49.core.service.TenantAdminService;
import auracore.key49.core.service.TenantAdminService.CreateTenantData;
import auracore.key49.core.validation.SriValidator;
import auracore.key49.signer.CertificateEncryptor;
import auracore.key49.signer.CertificateMetadataExtractor;
import auracore.key49.signer.CertificateMetadataExtractor.CertificateMetadata;
import auracore.key49.signer.SigningException;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

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

    @Inject
    TenantAdminService tenantAdminService;

    @Inject
    ApiKeyManagementService apiKeyManagementService;

    @Inject
    EmailVerificationService emailVerificationService;

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
     * Resultado del registro completo (paso 3 — confirmación).
     */
    public record RegistrationResult(boolean success, String error, String rawApiKey, UUID tenantId) {

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
     * Completa el registro: crea tenant, sube certificado, genera API key y
     * limpia la sesión temporal de Redis.
     *
     * @param registrationId ID de la sesión de registro
     * @return resultado con la API key raw (se muestra una sola vez)
     */
    @Transactional
    public RegistrationResult completeRegistration(String registrationId) {
        var regData = getRegistrationData(registrationId);
        if (regData == null) {
            return new RegistrationResult(false,
                    "Sesión de registro expirada. Inicie el registro nuevamente.", null, null);
        }

        // Verificar que se completaron los pasos previos
        var step = regData.getOrDefault("step", "0");
        if (!"2".equals(step)) {
            return new RegistrationResult(false,
                    "Debe completar todos los pasos anteriores antes de crear la cuenta.", null, null);
        }

        // Datos del paso 1
        var ruc = regData.get("ruc");
        var legalName = regData.get("legal_name");
        var email = regData.get("email");
        var passwordHash = regData.get("password_hash");

        if (ruc == null || legalName == null || email == null || passwordHash == null) {
            return new RegistrationResult(false,
                    "Datos de registro incompletos. Inicie el registro nuevamente.", null, null);
        }

        // Datos del paso 2
        var certP12Enc = regData.get("cert_p12_enc");
        var certPasswordEnc = regData.get("cert_password_enc");
        var certSubject = regData.get("cert_subject");
        var certSerial = regData.get("cert_serial");
        var certExpiresAt = regData.get("cert_expires_at");
        var environment = regData.getOrDefault("environment", "TEST");

        if (certP12Enc == null || certPasswordEnc == null) {
            return new RegistrationResult(false,
                    "Datos del certificado incompletos. Inicie el registro nuevamente.", null, null);
        }

        // Generar schema name basado en RUC
        var schemaName = "tenant_" + ruc;

        try {
            // 1. Crear tenant con plan DEMO
            var createData = new CreateTenantData(
                    ruc,
                    legalName,
                    null, // tradeName
                    legalName, // mainAddress (se actualizará después)
                    false, // requiredAccounting
                    null, // specialTaxpayer
                    false, // microEnterpriseRegime
                    null, // withholdingAgent
                    environment.toLowerCase(),
                    schemaName);

            var tenant = tenantAdminService.create(createData);

            // 2. Configurar plan DEMO (25 docs, 30 días)
            tenant.planType = "demo";
            tenant.documentQuota = 25;
            tenant.planStartsAt = Instant.now();
            tenant.planExpiresAt = Instant.now().plus(Duration.ofDays(30));

            // Rate limits según plan
            tenant.rateLimitRpm = PlanType.DEMO.totalRpm();
            tenant.rateLimitWriteRpm = PlanType.DEMO.writeRpm();
            tenant.rateLimitReadRpm = PlanType.DEMO.readRpm();

            // 3. Portal credentials (usar hash ya generado en paso 1)
            tenant.email = email;
            tenant.portalPasswordHash = passwordHash;

            // 4. Subir certificado cifrado
            tenant.certificateP12 = Base64.getDecoder().decode(certP12Enc);
            tenant.certificatePasswordEnc = Base64.getDecoder().decode(certPasswordEnc);
            tenant.certificateSubject = certSubject;
            tenant.certificateSerial = certSerial;
            if (certExpiresAt != null) {
                tenant.certificateExpiration = Instant.parse(certExpiresAt);
            }

            // 5. Marcar como pendiente hasta verificar email
            tenant.status = "pending";
            tenant.emailVerified = false;
            tenant.updatedAt = Instant.now();

            // 6. Generar API key (no funcionará hasta que el tenant esté activo)
            var createdKey = apiKeyManagementService.create(tenant.id,
                    new ApiKeyManagementService.CreateApiKeyData(
                            "Portal - Registro automático", "*", null));

            // 7. Enviar email de verificación
            emailVerificationService.sendVerificationEmail(tenant.id, email, legalName);

            // 8. Limpiar sesión de registro en Redis
            KeyCommands<String> keys = redisDS.key(String.class);
            keys.del(REG_PREFIX + registrationId);

            log.infof("Registration completed, pending verification | tenantId=%s ruc=%s schema=%s email=%s",
                    tenant.id, ruc, schemaName, email);
            return new RegistrationResult(true, null, createdKey.rawKey(), tenant.id);

        } catch (TenantAdminService.TenantException e) {
            log.errorf("Registration failed | registrationId=%s error=%s", registrationId, e.getMessage());
            return new RegistrationResult(false,
                    "Error al crear la cuenta: " + e.getMessage(), null, null);
        } catch (Exception e) {
            log.errorf(e, "Registration failed unexpectedly | registrationId=%s", registrationId);
            return new RegistrationResult(false,
                    "Error inesperado al crear la cuenta. Intente nuevamente.", null, null);
        }
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
