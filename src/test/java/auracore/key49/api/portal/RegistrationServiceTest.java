package auracore.key49.api.portal;

import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.service.PasswordHasher;
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

import java.util.Map;

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
}
