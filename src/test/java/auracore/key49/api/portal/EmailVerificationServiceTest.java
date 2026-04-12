package auracore.key49.api.portal;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.jboss.logging.Logger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.service.TenantCacheService;
import io.quarkus.mailer.reactive.ReactiveMailer;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.quarkus.redis.datasource.keys.KeyCommands;
import io.quarkus.redis.datasource.value.ValueCommands;
import io.smallrye.mutiny.Uni;

/**
 * Tests unitarios para EmailVerificationService — verificación de email en
 * autoregistro.
 */
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock
    Logger log;

    @Mock
    RedisDataSource redisDS;

    @Mock
    TenantRepository tenantRepository;

    @Mock
    TenantCacheService tenantCacheService;

    @Mock
    ReactiveMailer reactiveMailer;

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
    EmailVerificationService service;

    @Mock
    Template emailVerificationTemplate;

    @Mock
    TemplateInstance templateInstance;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        lenient().when(redisDS.hash(String.class, String.class, String.class)).thenReturn(hashCommands);
        lenient().when(redisDS.key(String.class)).thenReturn(keyCommands);
        lenient().when(redisDS.value(String.class, String.class)).thenReturn(valueCommands);

        setField("portalBaseUrl", "https://test.key49.ec");
        setField("fromAddress", "test@key49.ec");
        setField("sendTimeoutSeconds", 10);

        lenient().when(emailVerificationTemplate.data(anyString(), any())).thenReturn(templateInstance);
        lenient().when(templateInstance.data(anyString(), any())).thenReturn(templateInstance);
        lenient().when(templateInstance.render()).thenReturn("<html>Verify email</html>");
        setField("emailVerificationTemplate", emailVerificationTemplate);
    }

    private void setField(String fieldName, Object value) throws Exception {
        Field field = EmailVerificationService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(service, value);
    }

    @Nested
    @DisplayName("Enviar email de verificación")
    class SendVerificationEmail {

        @Test
        @DisplayName("falla con tenantId nulo")
        void failsWithNullTenantId() {
            var result = service.sendVerificationEmail(null, "test@test.com", "Test");
            assertFalse(result.success());
            assertNotNull(result.error());
        }

        @Test
        @DisplayName("falla con email nulo")
        void failsWithNullEmail() {
            var result = service.sendVerificationEmail(UUID.randomUUID(), null, "Test");
            assertFalse(result.success());
            assertNotNull(result.error());
        }

        @Test
        @DisplayName("falla con email vacío")
        void failsWithEmptyEmail() {
            var result = service.sendVerificationEmail(UUID.randomUUID(), "   ", "Test");
            assertFalse(result.success());
            assertNotNull(result.error());
        }

        @Test
        @DisplayName("falla si rate limited")
        @SuppressWarnings("unchecked")
        void failsIfRateLimited() {
            when(valueCommands.get("portal:verify-rate:user@test.com")).thenReturn("3");

            var result = service.sendVerificationEmail(UUID.randomUUID(), "user@test.com", "Test");

            assertFalse(result.success());
            assertTrue(result.error().contains("límite"));
        }

        @Test
        @DisplayName("éxito — genera token y envía email")
        @SuppressWarnings("unchecked")
        void successGeneratesTokenAndSendsEmail() {
            UUID tenantId = UUID.randomUUID();
            when(valueCommands.get("portal:verify-rate:admin@test.com")).thenReturn(null);
            when(reactiveMailer.send(any())).thenReturn(Uni.createFrom().voidItem());

            var result = service.sendVerificationEmail(tenantId, "admin@test.com", "Empresa Test");

            assertTrue(result.success(), "Expected success but got: " + result.error());
            assertNotNull(result.token());
            assertNull(result.error());

            // Verifica token guardado en Redis
            verify(hashCommands).hset(startsWith("portal:verify-email:"), argThat(map -> {
                @SuppressWarnings("unchecked")
                var m = (Map<String, String>) map;
                return "admin@test.com".equals(m.get("email"))
                        && tenantId.toString().equals(m.get("tenant_id"));
            }));

            // Verifica TTL de 24 horas
            verify(keyCommands).pexpire(startsWith("portal:verify-email:"), eq(86400000L));

            // Verifica email enviado
            verify(reactiveMailer).send(any());
        }

        @Test
        @DisplayName("éxito aunque falle envío de email — token queda en Redis")
        @SuppressWarnings("unchecked")
        void successEvenIfEmailFails() {
            UUID tenantId = UUID.randomUUID();
            when(valueCommands.get("portal:verify-rate:admin@test.com")).thenReturn(null);
            when(reactiveMailer.send(any())).thenThrow(new RuntimeException("SMTP error"));

            var result = service.sendVerificationEmail(tenantId, "admin@test.com", "Test");

            assertTrue(result.success());
            assertNotNull(result.token());
            verify(hashCommands).hset(startsWith("portal:verify-email:"), anyMap());
        }

        @Test
        @DisplayName("normaliza email a lowercase y stripped")
        @SuppressWarnings("unchecked")
        void normalizesEmail() {
            UUID tenantId = UUID.randomUUID();
            when(valueCommands.get("portal:verify-rate:admin@test.com")).thenReturn(null);
            when(reactiveMailer.send(any())).thenReturn(Uni.createFrom().voidItem());

            service.sendVerificationEmail(tenantId, "  Admin@Test.COM  ", "Test");

            verify(hashCommands).hset(startsWith("portal:verify-email:"), argThat(map -> {
                @SuppressWarnings("unchecked")
                var m = (Map<String, String>) map;
                return "admin@test.com".equals(m.get("email"));
            }));
        }

        @Test
        @DisplayName("incrementa contador de rate limit")
        @SuppressWarnings("unchecked")
        void incrementsRateCounter() {
            UUID tenantId = UUID.randomUUID();
            when(valueCommands.get("portal:verify-rate:admin@test.com")).thenReturn("1");
            when(keyCommands.pttl("portal:verify-rate:admin@test.com")).thenReturn(2000000L);
            when(reactiveMailer.send(any())).thenReturn(Uni.createFrom().voidItem());

            service.sendVerificationEmail(tenantId, "admin@test.com", "Test");

            verify(valueCommands).set("portal:verify-rate:admin@test.com", "2");
        }

        @Test
        @DisplayName("primer envío crea rate counter con TTL de 1 hora")
        @SuppressWarnings("unchecked")
        void firstSendCreatesRateCounter() {
            UUID tenantId = UUID.randomUUID();
            when(valueCommands.get("portal:verify-rate:admin@test.com")).thenReturn(null);
            when(reactiveMailer.send(any())).thenReturn(Uni.createFrom().voidItem());

            service.sendVerificationEmail(tenantId, "admin@test.com", "Test");

            verify(valueCommands).set("portal:verify-rate:admin@test.com", "1");
            verify(keyCommands).pexpire("portal:verify-rate:admin@test.com", 3600000L);
        }
    }

    @Nested
    @DisplayName("Verificar token")
    class VerifyToken {

        private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

        @Test
        @DisplayName("falla con token nulo")
        void failsWithNullToken() {
            var result = service.verify(null);
            assertFalse(result.success());
            assertNotNull(result.error());
        }

        @Test
        @DisplayName("falla con token vacío")
        void failsWithEmptyToken() {
            var result = service.verify("  ");
            assertFalse(result.success());
            assertNotNull(result.error());
        }

        @Test
        @DisplayName("falla si token no existe en Redis — expirado")
        @SuppressWarnings("unchecked")
        void failsIfTokenExpired() {
            when(hashCommands.hgetall("portal:verify-email:expired-token")).thenReturn(Map.of());

            var result = service.verify("expired-token");

            assertFalse(result.success());
            assertTrue(result.error().contains("expirado"));
        }

        @Test
        @DisplayName("falla si datos de verificación incompletos")
        @SuppressWarnings("unchecked")
        void failsIfDataIncomplete() {
            when(hashCommands.hgetall("portal:verify-email:bad-token")).thenReturn(
                    Map.of("email", "admin@test.com"));

            var result = service.verify("bad-token");

            assertFalse(result.success());
            assertTrue(result.error().contains("incompletos"));
            // Token se consume igualmente
            verify(keyCommands).del("portal:verify-email:bad-token");
        }

        @Test
        @DisplayName("falla si tenant no existe")
        @SuppressWarnings("unchecked")
        void failsIfTenantNotFound() {
            when(hashCommands.hgetall("portal:verify-email:valid-token")).thenReturn(
                    Map.of("email", "admin@test.com",
                            "tenant_id", TENANT_ID.toString(),
                            "created_at", Instant.now().toString()));
            when(tenantRepository.findById(TENANT_ID)).thenReturn(null);

            var result = service.verify("valid-token");

            assertFalse(result.success());
            assertTrue(result.error().contains("no fue encontrada"));
        }

        @Test
        @DisplayName("éxito — activa tenant y marca email verificado")
        @SuppressWarnings("unchecked")
        void successActivatesTenant() {
            var tenant = new Tenant();
            tenant.id = TENANT_ID;
            tenant.status = "pending";
            tenant.emailVerified = false;
            tenant.schemaName = "tenant_abc";

            when(hashCommands.hgetall("portal:verify-email:valid-token")).thenReturn(
                    Map.of("email", "admin@test.com",
                            "tenant_id", TENANT_ID.toString(),
                            "created_at", Instant.now().toString()));
            when(tenantRepository.findById(TENANT_ID)).thenReturn(tenant);

            var result = service.verify("valid-token");

            assertTrue(result.success(), "Expected success but got: " + result.error());
            assertNull(result.error());

            // Verifica cambios en el tenant
            assertTrue(tenant.emailVerified);
            assertEquals("active", tenant.status);
            assertNotNull(tenant.updatedAt);

            // Token consumido
            verify(keyCommands).del("portal:verify-email:valid-token");

            // Caché invalidada
            verify(tenantCacheService).invalidate(TENANT_ID, "tenant_abc");
        }

        @Test
        @DisplayName("idempotente — si ya está verificado retorna éxito")
        @SuppressWarnings("unchecked")
        void idempotentWhenAlreadyVerified() {
            var tenant = new Tenant();
            tenant.id = TENANT_ID;
            tenant.status = "active";
            tenant.emailVerified = true;
            tenant.schemaName = "tenant_abc";

            when(hashCommands.hgetall("portal:verify-email:valid-token")).thenReturn(
                    Map.of("email", "admin@test.com",
                            "tenant_id", TENANT_ID.toString(),
                            "created_at", Instant.now().toString()));
            when(tenantRepository.findById(TENANT_ID)).thenReturn(tenant);

            var result = service.verify("valid-token");

            assertTrue(result.success());
            // No modifica el tenant — ya verificado
            verify(tenantCacheService, never()).invalidate(any(), anyString());
        }

        @Test
        @DisplayName("falla si tenant no está en status pending")
        @SuppressWarnings("unchecked")
        void failsIfTenantNotPending() {
            var tenant = new Tenant();
            tenant.id = TENANT_ID;
            tenant.status = "suspended";
            tenant.emailVerified = false;
            tenant.schemaName = "tenant_abc";

            when(hashCommands.hgetall("portal:verify-email:valid-token")).thenReturn(
                    Map.of("email", "admin@test.com",
                            "tenant_id", TENANT_ID.toString(),
                            "created_at", Instant.now().toString()));
            when(tenantRepository.findById(TENANT_ID)).thenReturn(tenant);

            var result = service.verify("valid-token");

            assertFalse(result.success());
            assertTrue(result.error().contains("pendiente"));
        }

        @Test
        @DisplayName("strip del token antes de buscar")
        @SuppressWarnings("unchecked")
        void stripsTokenWhitespace() {
            when(hashCommands.hgetall("portal:verify-email:token-123")).thenReturn(Map.of());

            service.verify("  token-123  ");

            verify(hashCommands).hgetall("portal:verify-email:token-123");
        }
    }
}
