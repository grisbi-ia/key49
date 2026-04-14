package auracore.key49.api.portal;

import auracore.key49.core.model.ApiKey;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.service.ApiKeyManagementService;
import auracore.key49.core.service.PasswordHasher;
import auracore.key49.core.service.TenantAdminService;
import auracore.key49.core.service.TenantAdminService.CreateTenantData;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitarios para RegistrationService — wizard autoregistro paso 1.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock
    Logger log;

    @Mock
    RedisDataSource redisDS;

    @Mock
    TenantRepository tenantRepository;

    @Mock
    PasswordHasher passwordHasher;

    @Mock
    @SuppressWarnings("rawtypes")
    HashCommands hashCommands;

    @Mock
    @SuppressWarnings("rawtypes")
    KeyCommands keyCommands;

    @InjectMocks
    RegistrationService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        lenient().when(redisDS.hash(String.class, String.class, String.class)).thenReturn(hashCommands);
        lenient().when(redisDS.key(String.class)).thenReturn(keyCommands);
    }

    @Nested
    @DisplayName("Verificación de RUC")
    class VerifyRuc {

        @Test
        @DisplayName("RUC nulo retorna inválido")
        void nullRuc() {
            var result = service.verifyRuc(null);
            assertFalse(result.valid());
            assertFalse(result.registered());
        }

        @Test
        @DisplayName("RUC vacío retorna inválido")
        void emptyRuc() {
            var result = service.verifyRuc("  ");
            assertFalse(result.valid());
            assertFalse(result.registered());
        }

        @Test
        @DisplayName("RUC con formato inválido")
        void invalidFormat() {
            var result = service.verifyRuc("123");
            assertFalse(result.valid());
            assertFalse(result.registered());
        }

        @Test
        @DisplayName("RUC válido no registrado")
        void validNotRegistered() {
            when(tenantRepository.findByRuc("1790016919001")).thenReturn(null);

            var result = service.verifyRuc("1790016919001");
            assertTrue(result.valid());
            assertFalse(result.registered());
            assertEquals("RUC válido", result.message());
        }

        @Test
        @DisplayName("RUC válido ya registrado — bloquea registro")
        void validAlreadyRegistered() {
            var existingTenant = new Tenant();
            when(tenantRepository.findByRuc("1790016919001")).thenReturn(existingTenant);

            var result = service.verifyRuc("1790016919001");
            assertTrue(result.valid());
            assertTrue(result.registered());
            assertTrue(result.message().contains("ya se encuentra registrado"));
        }
    }

    @Nested
    @DisplayName("Guardar paso 1")
    class SaveStep1 {

        private static final String VALID_RUC = "1790016919001";
        private static final String VALID_NAME = "Mi Empresa S.A.";
        private static final String VALID_EMAIL = "admin@empresa.com";
        private static final String VALID_PASSWORD = "password123";

        @Test
        @DisplayName("paso 1 exitoso — datos guardados en Redis")
        @SuppressWarnings("unchecked")
        void successfulStep1() {
            when(tenantRepository.findByRuc(VALID_RUC)).thenReturn(null);
            when(tenantRepository.findByEmail(VALID_EMAIL)).thenReturn(null);
            when(passwordHasher.hash(VALID_PASSWORD)).thenReturn("$2y$12$hashedvalue");

            var result = service.saveStep1(VALID_RUC, VALID_NAME, VALID_EMAIL,
                    VALID_PASSWORD, VALID_PASSWORD);

            assertTrue(result.success());
            assertNull(result.error());
            assertNotNull(result.registrationId());

            verify(hashCommands).hset(startsWith("portal:registration:"), anyMap());
            verify(keyCommands).pexpire(startsWith("portal:registration:"), eq(1800000L));
        }

        @Test
        @DisplayName("falla si RUC ya registrado")
        void failsIfRucRegistered() {
            when(tenantRepository.findByRuc(VALID_RUC)).thenReturn(new Tenant());

            var result = service.saveStep1(VALID_RUC, VALID_NAME, VALID_EMAIL,
                    VALID_PASSWORD, VALID_PASSWORD);

            assertFalse(result.success());
            assertTrue(result.error().contains("ya se encuentra registrado"));
            assertNull(result.registrationId());
            verify(hashCommands, never()).hset(anyString(), anyMap());
        }

        @Test
        @DisplayName("falla si RUC inválido")
        void failsIfRucInvalid() {
            var result = service.saveStep1("invalid", VALID_NAME, VALID_EMAIL,
                    VALID_PASSWORD, VALID_PASSWORD);

            assertFalse(result.success());
            assertTrue(result.error().contains("inválido"));
        }

        @Test
        @DisplayName("falla si razón social muy corta")
        void failsIfNameTooShort() {
            when(tenantRepository.findByRuc(VALID_RUC)).thenReturn(null);

            var result = service.saveStep1(VALID_RUC, "AB", VALID_EMAIL,
                    VALID_PASSWORD, VALID_PASSWORD);

            assertFalse(result.success());
            assertTrue(result.error().contains("razón social"));
        }

        @Test
        @DisplayName("falla si email inválido")
        void failsIfEmailInvalid() {
            when(tenantRepository.findByRuc(VALID_RUC)).thenReturn(null);

            var result = service.saveStep1(VALID_RUC, VALID_NAME, "not-an-email",
                    VALID_PASSWORD, VALID_PASSWORD);

            assertFalse(result.success());
            assertTrue(result.error().contains("email"));
        }

        @Test
        @DisplayName("falla si email ya registrado")
        void failsIfEmailRegistered() {
            when(tenantRepository.findByRuc(VALID_RUC)).thenReturn(null);
            when(tenantRepository.findByEmail(VALID_EMAIL)).thenReturn(new Tenant());

            var result = service.saveStep1(VALID_RUC, VALID_NAME, VALID_EMAIL,
                    VALID_PASSWORD, VALID_PASSWORD);

            assertFalse(result.success());
            assertTrue(result.error().contains("email ya se encuentra registrado"));
        }

        @Test
        @DisplayName("falla si contraseña muy corta")
        void failsIfPasswordTooShort() {
            when(tenantRepository.findByRuc(VALID_RUC)).thenReturn(null);
            when(tenantRepository.findByEmail(VALID_EMAIL)).thenReturn(null);

            var result = service.saveStep1(VALID_RUC, VALID_NAME, VALID_EMAIL,
                    "short", "short");

            assertFalse(result.success());
            assertTrue(result.error().contains("8 caracteres"));
        }

        @Test
        @DisplayName("falla si contraseñas no coinciden")
        void failsIfPasswordsMismatch() {
            when(tenantRepository.findByRuc(VALID_RUC)).thenReturn(null);
            when(tenantRepository.findByEmail(VALID_EMAIL)).thenReturn(null);

            var result = service.saveStep1(VALID_RUC, VALID_NAME, VALID_EMAIL,
                    VALID_PASSWORD, "different123");

            assertFalse(result.success());
            assertTrue(result.error().contains("no coinciden"));
        }
    }

    @Nested
    @DisplayName("Obtener datos de registro")
    class GetRegistrationData {

        @Test
        @DisplayName("retorna datos si existen en Redis")
        @SuppressWarnings("unchecked")
        void returnsDataIfExists() {
            var data = Map.of("ruc", "1790016919001", "legal_name", "Test", "step", "1");
            when(hashCommands.hgetall("portal:registration:abc-123")).thenReturn(data);

            var result = service.getRegistrationData("abc-123");
            assertNotNull(result);
            assertEquals("1790016919001", result.get("ruc"));
        }

        @Test
        @DisplayName("retorna null si no existe")
        @SuppressWarnings("unchecked")
        void returnsNullIfNotExists() {
            when(hashCommands.hgetall("portal:registration:expired")).thenReturn(Map.of());

            var result = service.getRegistrationData("expired");
            assertNull(result);
        }

        @Test
        @DisplayName("retorna null si registrationId es nulo")
        void returnsNullIfNull() {
            var result = service.getRegistrationData(null);
            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Guardar paso 2 — Certificado")
    class SaveStep2 {

        private static final String REG_ID = "reg-test-123";
        private static final String TEST_MASTER_KEY = "Kt+uSavMguKGLq2ese9Zj0qbk5U97/rGPIaW0TCqask=";
        private static final String TEST_CERT_PASSWORD = "test1234";

        @BeforeEach
        void setMasterKey() throws Exception {
            Field field = RegistrationService.class.getDeclaredField("masterKeyBase64");
            field.setAccessible(true);
            field.set(service, TEST_MASTER_KEY);
        }

        private byte[] loadTestCertificate() {
            try (InputStream is = getClass().getResourceAsStream("/test-cert.p12")) {
                if (is == null) {
                    throw new IllegalStateException("test-cert.p12 not found in test resources");
                }
                return is.readAllBytes();
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        }

        @SuppressWarnings("unchecked")
        private void stubExistingRegistration() {
            when(hashCommands.hgetall("portal:registration:" + REG_ID))
                    .thenReturn(Map.of("ruc", "1790016919001", "step", "1"));
        }

        @Test
        @DisplayName("falla si sesión de registro no existe")
        @SuppressWarnings("unchecked")
        void failsIfSessionExpired() {
            when(hashCommands.hgetall("portal:registration:" + REG_ID)).thenReturn(Map.of());

            var result = service.saveStep2(REG_ID, new byte[]{0x30, 0x01}, TEST_CERT_PASSWORD, "TEST");

            assertFalse(result.success());
            assertTrue(result.error().contains("expirada"));
        }

        @Test
        @DisplayName("falla si archivo es nulo")
        void failsIfFileNull() {
            stubExistingRegistration();

            var result = service.saveStep2(REG_ID, null, TEST_CERT_PASSWORD, "TEST");

            assertFalse(result.success());
            assertTrue(result.error().contains("seleccionar"));
        }

        @Test
        @DisplayName("falla si archivo es vacío")
        void failsIfFileEmpty() {
            stubExistingRegistration();

            var result = service.saveStep2(REG_ID, new byte[0], TEST_CERT_PASSWORD, "TEST");

            assertFalse(result.success());
            assertTrue(result.error().contains("seleccionar"));
        }

        @Test
        @DisplayName("falla si archivo excede 50 KB")
        void failsIfFileTooLarge() {
            stubExistingRegistration();
            var largeFile = new byte[51 * 1024];
            largeFile[0] = 0x30;

            var result = service.saveStep2(REG_ID, largeFile, TEST_CERT_PASSWORD, "TEST");

            assertFalse(result.success());
            assertTrue(result.error().contains("50 KB"));
        }

        @Test
        @DisplayName("falla si magic byte no es 0x30")
        void failsIfInvalidMagicByte() {
            stubExistingRegistration();

            var result = service.saveStep2(REG_ID, new byte[]{0x50, 0x4B}, TEST_CERT_PASSWORD, "TEST");

            assertFalse(result.success());
            assertTrue(result.error().contains("PKCS#12"));
        }

        @Test
        @DisplayName("falla si contraseña del certificado vacía")
        void failsIfCertPasswordEmpty() {
            stubExistingRegistration();

            var result = service.saveStep2(REG_ID, new byte[]{0x30, 0x01}, "", "TEST");

            assertFalse(result.success());
            assertTrue(result.error().contains("contraseña del certificado"));
        }

        @Test
        @DisplayName("falla si ambiente inválido")
        void failsIfInvalidEnvironment() {
            stubExistingRegistration();

            var result = service.saveStep2(REG_ID, new byte[]{0x30, 0x01}, TEST_CERT_PASSWORD, "STAGING");

            assertFalse(result.success());
            assertTrue(result.error().contains("ambiente"));
        }

        @Test
        @DisplayName("falla si certificado con contraseña incorrecta")
        void failsIfWrongPassword() {
            stubExistingRegistration();
            var p12 = loadTestCertificate();

            var result = service.saveStep2(REG_ID, p12, "wrong_password", "TEST");

            assertFalse(result.success());
            assertTrue(result.error().contains("No se pudo leer el certificado"));
        }

        @Test
        @DisplayName("exitoso con certificado válido — guarda cifrado en Redis")
        @SuppressWarnings("unchecked")
        void successWithValidCertificate() {
            stubExistingRegistration();
            var p12 = loadTestCertificate();

            var result = service.saveStep2(REG_ID, p12, TEST_CERT_PASSWORD, "TEST");

            assertTrue(result.success(), "Expected success but got: " + result.error());
            assertNull(result.error());
            assertNotNull(result.metadata());
            assertNotNull(result.metadata().subject());
            assertNotNull(result.metadata().serial());
            assertTrue(result.metadata().valid());

            verify(hashCommands).hset(eq("portal:registration:" + REG_ID), anyMap());
            verify(keyCommands).pexpire(eq("portal:registration:" + REG_ID), eq(1800000L));
        }

        @Test
        @DisplayName("acepta ambiente PRODUCTION")
        @SuppressWarnings("unchecked")
        void acceptsProductionEnvironment() {
            stubExistingRegistration();
            var p12 = loadTestCertificate();

            var result = service.saveStep2(REG_ID, p12, TEST_CERT_PASSWORD, "PRODUCTION");

            assertTrue(result.success(), "Expected success but got: " + result.error());
        }
    }

    @Nested
    @DisplayName("Completar registro — paso 3")
    class CompleteRegistration {

        private static final String REG_ID = "reg-test-789";
        private static final String TEST_MASTER_KEY = "Kt+uSavMguKGLq2ese9Zj0qbk5U97/rGPIaW0TCqask=";

        @Mock
        TenantAdminService tenantAdminService;

        @Mock
        ApiKeyManagementService apiKeyManagementService;

        @Mock
        EmailVerificationService emailVerificationService;

        @BeforeEach
        void injectDependencies() throws Exception {
            Field mkField = RegistrationService.class.getDeclaredField("masterKeyBase64");
            mkField.setAccessible(true);
            mkField.set(service, TEST_MASTER_KEY);

            Field tasField = RegistrationService.class.getDeclaredField("tenantAdminService");
            tasField.setAccessible(true);
            tasField.set(service, tenantAdminService);

            Field akmsField = RegistrationService.class.getDeclaredField("apiKeyManagementService");
            akmsField.setAccessible(true);
            akmsField.set(service, apiKeyManagementService);

            Field evsField = RegistrationService.class.getDeclaredField("emailVerificationService");
            evsField.setAccessible(true);
            evsField.set(service, emailVerificationService);
        }

        private Map<String, String> fullRegistrationData() {
            var data = new HashMap<String, String>();
            data.put("ruc", "1790016919001");
            data.put("legal_name", "Empresa Test S.A.");
            data.put("email", "admin@test.com");
            data.put("password_hash", "$2y$12$hashedvalue");
            data.put("cert_p12_enc", "dGVzdC1jZXJ0LWVuY3J5cHRlZA=="); // base64 test data
            data.put("cert_password_enc", "dGVzdC1wd2QtZW5jcnlwdGVk"); // base64 test data
            data.put("cert_subject", "CN=TEST SUBJECT");
            data.put("cert_serial", "123456789");
            data.put("cert_expires_at", "2026-12-31T23:59:59Z");
            data.put("environment", "TEST");
            data.put("step", "2");
            return data;
        }

        @SuppressWarnings("unchecked")
        private void stubRegistrationData(Map<String, String> data) {
            when(hashCommands.hgetall("portal:registration:" + REG_ID)).thenReturn(data);
        }

        private Tenant stubTenantCreation() {
            var tenant = new Tenant();
            tenant.id = UUID.randomUUID();
            tenant.ruc = "1790016919001";
            tenant.status = "active";
            when(tenantAdminService.create(any(CreateTenantData.class))).thenReturn(tenant);
            lenient().when(emailVerificationService.sendVerificationEmail(any(), anyString(), anyString()))
                    .thenReturn(new EmailVerificationService.SendResult(true, null, "test-token"));
            return tenant;
        }

        private void stubApiKeyCreation(UUID tenantId) {
            var apiKey = new ApiKey();
            apiKey.id = UUID.randomUUID();
            apiKey.tenantId = tenantId;
            apiKey.keyPrefix = "k49";
            when(apiKeyManagementService.create(eq(tenantId), any(ApiKeyManagementService.CreateApiKeyData.class)))
                    .thenReturn(new ApiKeyManagementService.CreatedApiKey(apiKey, "k49_rawkey12345678901234567"));
        }

        @Test
        @DisplayName("falla si sesión de registro no existe")
        @SuppressWarnings("unchecked")
        void failsIfSessionExpired() {
            when(hashCommands.hgetall("portal:registration:" + REG_ID)).thenReturn(Map.of());

            var result = service.completeRegistration(REG_ID);

            assertFalse(result.success());
            assertTrue(result.error().contains("expirada"));
            assertNull(result.rawApiKey());
            assertNull(result.tenantId());
        }

        @Test
        @DisplayName("falla si paso no es 2")
        void failsIfStepNotComplete() {
            var data = fullRegistrationData();
            data.put("step", "1");
            stubRegistrationData(data);

            var result = service.completeRegistration(REG_ID);

            assertFalse(result.success());
            assertTrue(result.error().contains("completar todos los pasos"));
            verifyNoInteractions(tenantAdminService);
        }

        @Test
        @DisplayName("falla si datos del paso 1 incompletos")
        void failsIfStep1DataMissing() {
            var data = fullRegistrationData();
            data.remove("ruc");
            stubRegistrationData(data);

            var result = service.completeRegistration(REG_ID);

            assertFalse(result.success());
            assertTrue(result.error().contains("incompletos"));
            verifyNoInteractions(tenantAdminService);
        }

        @Test
        @DisplayName("falla si datos del certificado incompletos")
        void failsIfCertDataMissing() {
            var data = fullRegistrationData();
            data.remove("cert_p12_enc");
            stubRegistrationData(data);

            var result = service.completeRegistration(REG_ID);

            assertFalse(result.success());
            assertTrue(result.error().contains("certificado incompletos"));
            verifyNoInteractions(tenantAdminService);
        }

        @Test
        @DisplayName("exitoso — crea tenant, genera API key y limpia Redis")
        void successfulRegistration() {
            var data = fullRegistrationData();
            stubRegistrationData(data);
            var tenant = stubTenantCreation();
            stubApiKeyCreation(tenant.id);

            var result = service.completeRegistration(REG_ID);

            assertTrue(result.success(), "Expected success but got: " + result.error());
            assertNotNull(result.rawApiKey());
            assertEquals(tenant.id, result.tenantId());
            assertTrue(result.rawApiKey().startsWith("k49_"));

            // Verifica creación de tenant con datos correctos
            verify(tenantAdminService).create(argThat(d
                    -> "1790016919001".equals(d.ruc())
                    && "Empresa Test S.A.".equals(d.legalName())
                    && "test".equals(d.environment())));

            // Verifica que se generó API key
            verify(apiKeyManagementService).create(eq(tenant.id), any());

            // Verifica limpieza de Redis
            verify(keyCommands).del("portal:registration:" + REG_ID);

            // Verifica email de verificación enviado
            verify(emailVerificationService).sendVerificationEmail(eq(tenant.id), eq("admin@test.com"), eq("Empresa Test S.A."));

            // Verifica campos del tenant
            assertEquals("pending", tenant.status);
            assertFalse(tenant.emailVerified);
            assertEquals("admin@test.com", tenant.email);
            assertEquals("$2y$12$hashedvalue", tenant.portalPasswordHash);
            assertEquals("demo", tenant.planType);
            assertEquals(25, tenant.documentQuota);
            assertNotNull(tenant.planStartsAt);
            assertNotNull(tenant.planExpiresAt);
            assertEquals("CN=TEST SUBJECT", tenant.certificateSubject);

            // Verifica rate limits según plan DEMO
            assertEquals(10, tenant.rateLimitWriteRpm);
            assertEquals(30, tenant.rateLimitReadRpm);
            assertEquals(40, tenant.rateLimitRpm);
        }

        @Test
        @DisplayName("falla si TenantAdminService lanza TenantException")
        void failsOnTenantException() {
            stubRegistrationData(fullRegistrationData());
            when(tenantAdminService.create(any(CreateTenantData.class)))
                    .thenThrow(new TenantAdminService.TenantException(
                            "DUPLICATE_RUC", "RUC duplicado", 409));

            var result = service.completeRegistration(REG_ID);

            assertFalse(result.success());
            assertTrue(result.error().contains("RUC duplicado"));
            assertNull(result.rawApiKey());
        }

        @Test
        @DisplayName("falla si ocurre excepción inesperada")
        void failsOnUnexpectedException() {
            stubRegistrationData(fullRegistrationData());
            when(tenantAdminService.create(any(CreateTenantData.class)))
                    .thenThrow(new RuntimeException("Database connection lost"));

            var result = service.completeRegistration(REG_ID);

            assertFalse(result.success());
            assertTrue(result.error().contains("inesperado"));
            assertNull(result.rawApiKey());
        }

        @Test
        @DisplayName("usa environment PRODUCTION para tenant")
        void productionEnvironment() {
            var data = fullRegistrationData();
            data.put("environment", "PRODUCTION");
            stubRegistrationData(data);
            var tenant = stubTenantCreation();

            var apiKey = new ApiKey();
            apiKey.id = UUID.randomUUID();
            apiKey.tenantId = tenant.id;
            apiKey.keyPrefix = "k49";
            when(apiKeyManagementService.create(eq(tenant.id), any()))
                    .thenReturn(new ApiKeyManagementService.CreatedApiKey(apiKey, "k49_rawkey12345678901234567"));

            var result = service.completeRegistration(REG_ID);

            assertTrue(result.success(), "Expected success but got: " + result.error());
            assertTrue(result.rawApiKey().startsWith("k49_"));

            verify(tenantAdminService).create(argThat(d
                    -> "production".equals(d.environment())));
        }
    }
}
