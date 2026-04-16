package auracore.key49.api.portal;

import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.service.PasswordHasher;
import auracore.key49.notify.email.PlatformEmailService;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitarios para PasswordResetService — recuperación de contraseña del
 * portal.
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    Logger log;

    @Mock
    RedisDataSource redisDS;

    @Mock
    TenantRepository tenantRepository;

    @Mock
    PasswordHasher passwordHasher;

    @Mock
    PlatformEmailService platformEmailService;

    @Mock
    @SuppressWarnings("rawtypes")
    HashCommands hashCommands;

    @Mock
    @SuppressWarnings("rawtypes")
    KeyCommands keyCommands;

    @Mock
    @SuppressWarnings("rawtypes")
    ValueCommands valueCommands;

    @InjectMocks
    PasswordResetService service;

    @Mock
    Template passwordResetEmailTemplate;

    @Mock
    TemplateInstance templateInstance;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redisDS.hash(String.class, String.class, String.class)).thenReturn(hashCommands);
        lenient().when(redisDS.key(String.class)).thenReturn(keyCommands);
        lenient().when(redisDS.value(String.class, String.class)).thenReturn(valueCommands);

        // Inyectar config properties
        setField("portalBaseUrl", "https://test.key49.ec");

        // Mock Qute template
        lenient().when(passwordResetEmailTemplate.data(anyString(), any())).thenReturn(templateInstance);
        lenient().when(templateInstance.data(anyString(), any())).thenReturn(templateInstance);
        lenient().when(templateInstance.render()).thenReturn("<html>Reset email</html>");
        setField("passwordResetEmailTemplate", passwordResetEmailTemplate);
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = PasswordResetService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }

    @Nested
    @DisplayName("Solicitar recuperación")
    class RequestReset {

        @Test
        @DisplayName("falla con email nulo")
        void failsWithNullEmail() {
            var result = service.requestReset(null);
            assertFalse(result.success());
            assertTrue(result.error().contains("obligatorio"));
        }

        @Test
        @DisplayName("falla con email vacío")
        void failsWithEmptyEmail() {
            var result = service.requestReset("   ");
            assertFalse(result.success());
            assertTrue(result.error().contains("obligatorio"));
        }

        @Test
        @DisplayName("falla con email inválido")
        void failsWithInvalidEmail() {
            var result = service.requestReset("not-an-email");
            assertFalse(result.success());
            assertTrue(result.error().contains("válido"));
        }

        @Test
        @DisplayName("falla si rate limited")
        @SuppressWarnings("unchecked")
        void failsIfRateLimited() {
            when(valueCommands.get("portal:reset-rate:user@test.com")).thenReturn("3");

            var result = service.requestReset("user@test.com");

            assertFalse(result.success());
            assertTrue(result.error().contains("límite"));
        }

        @Test
        @DisplayName("éxito aunque email no exista — no revela información")
        @SuppressWarnings("unchecked")
        void successEvenIfEmailNotFound() {
            when(valueCommands.get("portal:reset-rate:unknown@test.com")).thenReturn(null);
            when(tenantRepository.findByEmail("unknown@test.com")).thenReturn(null);

            var result = service.requestReset("unknown@test.com");

            assertTrue(result.success());
            assertNull(result.error());
            // No debe intentar guardar token en Redis ni enviar email
            verify(hashCommands, never()).hset(anyString(), anyMap());
        }

        @Test
        @DisplayName("éxito con email registrado — genera token y envía email")
        @SuppressWarnings("unchecked")
        void successWithRegisteredEmail() {
            var tenant = new Tenant();
            tenant.id = UUID.randomUUID();
            tenant.legalName = "Empresa Test";
            tenant.status = "active";

            when(valueCommands.get("portal:reset-rate:admin@test.com")).thenReturn(null);
            when(tenantRepository.findByEmail("admin@test.com")).thenReturn(tenant);

            var result = service.requestReset("admin@test.com");

            assertTrue(result.success(), "Expected success but got: " + result.error());
            assertNull(result.error());

            // Verifica token guardado en Redis
            verify(hashCommands).hset(startsWith("portal:reset:"), argThat(map -> {
                @SuppressWarnings("unchecked")
                var m = (Map<String, String>) map;
                return "admin@test.com".equals(m.get("email"))
                        && tenant.id.toString().equals(m.get("tenant_id"));
            }));

            // Verifica TTL de 30 min
            verify(keyCommands).pexpire(startsWith("portal:reset:"), eq(1800000L));

            // Verifica email enviado vía PlatformEmailService
            verify(platformEmailService).sendHtml(eq("admin@test.com"), anyString(), anyString());
        }

        @Test
        @DisplayName("incrementa contador de rate limit")
        @SuppressWarnings("unchecked")
        void incrementsRateCounter() {
            when(valueCommands.get("portal:reset-rate:admin@test.com")).thenReturn("1");
            when(keyCommands.pttl("portal:reset-rate:admin@test.com")).thenReturn(2000000L);

            var tenant = new Tenant();
            tenant.id = UUID.randomUUID();
            tenant.status = "active";
            when(tenantRepository.findByEmail("admin@test.com")).thenReturn(tenant);

            service.requestReset("admin@test.com");

            // Verifica que se incrementó el contador
            verify(valueCommands).set("portal:reset-rate:admin@test.com", "2");
        }

        @Test
        @DisplayName("no envía email si tenant inactivo")
        @SuppressWarnings("unchecked")
        void noEmailIfTenantInactive() {
            var tenant = new Tenant();
            tenant.id = UUID.randomUUID();
            tenant.status = "suspended";

            when(valueCommands.get("portal:reset-rate:admin@test.com")).thenReturn(null);
            when(tenantRepository.findByEmail("admin@test.com")).thenReturn(tenant);

            var result = service.requestReset("admin@test.com");

            assertTrue(result.success()); // No revela que el tenant está inactivo
            verify(platformEmailService, never()).sendHtml(any(), any(), any());
        }

        @Test
        @DisplayName("éxito aunque falle el envío de email")
        @SuppressWarnings("unchecked")
        void successEvenIfEmailFails() {
            var tenant = new Tenant();
            tenant.id = UUID.randomUUID();
            tenant.legalName = "Empresa Test";
            tenant.status = "active";

            when(valueCommands.get("portal:reset-rate:admin@test.com")).thenReturn(null);
            when(tenantRepository.findByEmail("admin@test.com")).thenReturn(tenant);
            doThrow(new RuntimeException("SMTP error"))
                    .when(platformEmailService).sendHtml(any(), any(), any());

            var result = service.requestReset("admin@test.com");

            assertTrue(result.success()); // No revela el error de envío
        }
    }

    @Nested
    @DisplayName("Validar token")
    class ValidateToken {

        @Test
        @DisplayName("falla con token nulo")
        void failsWithNullToken() {
            var result = service.validateToken(null);
            assertFalse(result.valid());
        }

        @Test
        @DisplayName("falla con token vacío")
        void failsWithEmptyToken() {
            var result = service.validateToken("  ");
            assertFalse(result.valid());
        }

        @Test
        @DisplayName("falla si token no existe en Redis")
        @SuppressWarnings("unchecked")
        void failsIfTokenExpired() {
            when(hashCommands.hgetall("portal:reset:abc-123")).thenReturn(Map.of());

            var result = service.validateToken("abc-123");

            assertFalse(result.valid());
            assertTrue(result.error().contains("expirado"));
        }

        @Test
        @DisplayName("éxito con token válido")
        @SuppressWarnings("unchecked")
        void successWithValidToken() {
            when(hashCommands.hgetall("portal:reset:valid-token")).thenReturn(
                    Map.of("email", "admin@test.com", "tenant_id", UUID.randomUUID().toString()));

            var result = service.validateToken("valid-token");

            assertTrue(result.valid());
            assertNull(result.error());
            assertEquals("admin@test.com", result.email());
        }
    }

    @Nested
    @DisplayName("Restablecer contraseña")
    class ResetPasswordTests {

        private static final String TOKEN = "valid-reset-token";
        private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

        @SuppressWarnings("unchecked")
        private void stubValidToken() {
            when(hashCommands.hgetall("portal:reset:" + TOKEN)).thenReturn(
                    Map.of("email", "admin@test.com",
                            "tenant_id", TENANT_ID.toString(),
                            "created_at", "2026-04-12T00:00:00Z"));
        }

        private Tenant stubActiveTenant() {
            var tenant = new Tenant();
            tenant.id = TENANT_ID;
            tenant.status = "active";
            tenant.portalPasswordHash = "$2a$12$oldhash";
            when(tenantRepository.findById(TENANT_ID)).thenReturn(tenant);
            return tenant;
        }

        @Test
        @DisplayName("falla con token nulo")
        void failsWithNullToken() {
            var result = service.resetPassword(null, "newpass123", "newpass123");
            assertFalse(result.success());
        }

        @Test
        @DisplayName("falla si contraseña muy corta")
        void failsIfPasswordTooShort() {
            var result = service.resetPassword(TOKEN, "short", "short");
            assertFalse(result.success());
            assertTrue(result.error().contains("8 caracteres"));
        }

        @Test
        @DisplayName("falla si contraseñas no coinciden")
        void failsIfPasswordsMismatch() {
            var result = service.resetPassword(TOKEN, "password123", "password456");
            assertFalse(result.success());
            assertTrue(result.error().contains("no coinciden"));
        }

        @Test
        @DisplayName("falla si token expirado")
        @SuppressWarnings("unchecked")
        void failsIfTokenExpired() {
            when(hashCommands.hgetall("portal:reset:" + TOKEN)).thenReturn(Map.of());

            var result = service.resetPassword(TOKEN, "password123", "password123");

            assertFalse(result.success());
            assertTrue(result.error().contains("expirado"));
        }

        @Test
        @DisplayName("falla si tenant no existe")
        @SuppressWarnings("unchecked")
        void failsIfTenantNotFound() {
            stubValidToken();
            when(tenantRepository.findById(TENANT_ID)).thenReturn(null);

            var result = service.resetPassword(TOKEN, "password123", "password123");

            assertFalse(result.success());
            assertTrue(result.error().contains("no se encuentra activa"));
            // Token debe haber sido eliminado
            verify(keyCommands).del("portal:reset:" + TOKEN);
        }

        @Test
        @DisplayName("falla si tenant inactivo")
        @SuppressWarnings("unchecked")
        void failsIfTenantInactive() {
            stubValidToken();
            var tenant = new Tenant();
            tenant.id = TENANT_ID;
            tenant.status = "suspended";
            when(tenantRepository.findById(TENANT_ID)).thenReturn(tenant);

            var result = service.resetPassword(TOKEN, "password123", "password123");

            assertFalse(result.success());
            assertTrue(result.error().contains("no se encuentra activa"));
        }

        @Test
        @DisplayName("éxito — actualiza hash, elimina token")
        @SuppressWarnings("unchecked")
        void successfulReset() {
            stubValidToken();
            var tenant = stubActiveTenant();
            when(passwordHasher.hash("newpassword1")).thenReturn("$2a$12$newhash");

            var result = service.resetPassword(TOKEN, "newpassword1", "newpassword1");

            assertTrue(result.success(), "Expected success but got: " + result.error());
            assertNull(result.error());

            // Verifica que se eliminó el token
            verify(keyCommands).del("portal:reset:" + TOKEN);

            // Verifica que se actualizó el hash
            assertEquals("$2a$12$newhash", tenant.portalPasswordHash);
            assertNotNull(tenant.updatedAt);
        }

        @Test
        @DisplayName("token se elimina antes de actualizar BD — evita reutilización")
        @SuppressWarnings("unchecked")
        void tokenDeletedBeforeDbUpdate() {
            stubValidToken();
            stubActiveTenant();
            when(passwordHasher.hash("newpassword1")).thenReturn("$2a$12$newhash");

            service.resetPassword(TOKEN, "newpassword1", "newpassword1");

            // Verificar orden: primero del, luego findById
            var inOrder = inOrder(keyCommands, tenantRepository);
            inOrder.verify(keyCommands).del("portal:reset:" + TOKEN);
            inOrder.verify(tenantRepository).findById(TENANT_ID);
        }
    }
}
