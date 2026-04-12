package auracore.key49.core.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.jboss.logging.Logger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import auracore.key49.core.model.PlanRenewal;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.PlanRenewalRepository;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.notify.plan.PlanAlertService;

/**
 * Tests unitarios para PlanExpirationService — job programado de expiración y
 * auto-renovación de planes.
 */
@ExtendWith(MockitoExtension.class)
class PlanExpirationServiceTest {

    @Mock
    Logger log;

    @Mock
    TenantRepository tenantRepository;

    @Mock
    PlanRenewalRepository renewalRepository;

    @Mock
    TenantCacheService tenantCacheService;

    @Mock
    PlanAlertService planAlertService;

    @InjectMocks
    PlanExpirationService service;

    private Instant now;
    private UUID tenantId;
    private Tenant demoTenant;
    private Tenant starterTenant;
    private Tenant enterpriseTenant;

    @BeforeEach
    void setUp() {
        now = Instant.now();
        tenantId = UUID.randomUUID();

        demoTenant = createTenant("demo", 25);
        starterTenant = createTenant("starter", 100);
        enterpriseTenant = createTenant("enterprise", 5000);
    }

    private Tenant createTenant(String planType, int quota) {
        var tenant = new Tenant();
        tenant.id = UUID.randomUUID();
        tenant.ruc = "1790012345001";
        tenant.legalName = "Empresa Test S.A.";
        tenant.planType = planType;
        tenant.documentQuota = quota;
        tenant.documentsUsed = 42;
        tenant.planStartsAt = now.minus(30, ChronoUnit.DAYS);
        tenant.planExpiresAt = now.minus(1, ChronoUnit.HOURS);
        tenant.schemaName = "tenant_abc";
        tenant.status = "active";
        tenant.webhookUrl = "https://hooks.example.com/key49";
        tenant.webhookSecret = "secret123";
        tenant.replyEmail = "admin@example.com";
        tenant.createdAt = now.minus(60, ChronoUnit.DAYS);
        tenant.updatedAt = now.minus(1, ChronoUnit.DAYS);
        return tenant;
    }

    @Nested
    @DisplayName("checkAndProcess")
    class CheckAndProcess {

        @Test
        @DisplayName("No procesa nada cuando no hay tenants expirados")
        void noExpiredTenants() {
            when(tenantRepository.findExpiredActive(now)).thenReturn(List.of());

            var result = service.checkAndProcess(now);

            assertEquals(0, result.expired());
            assertEquals(0, result.renewed());
            verifyNoInteractions(tenantCacheService, planAlertService, renewalRepository);
        }

        @Test
        @DisplayName("Expira plan DEMO y auto-renueva ENTERPRISE en la misma ejecución")
        void mixedExpirationAndRenewal() {
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(demoTenant, enterpriseTenant));

            var result = service.checkAndProcess(now);

            assertEquals(1, result.expired());
            assertEquals(1, result.renewed());
        }

        @Test
        @DisplayName("Continúa procesando otros tenants si uno falla")
        void continuesOnError() {
            var failingTenant = createTenant("starter", 100);
            failingTenant.id = null; // Causa NullPointerException en fireAlert

            // Make cache invalidation throw for the failing tenant
            doThrow(new RuntimeException("Simulated error"))
                    .when(tenantCacheService).invalidate(isNull(), anyString());
            doNothing().when(tenantCacheService)
                    .invalidate(eq(enterpriseTenant.id), anyString());

            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(failingTenant, enterpriseTenant));

            var result = service.checkAndProcess(now);

            // failingTenant failed, enterpriseTenant succeeded
            assertEquals(0, result.expired());
            assertEquals(1, result.renewed());
        }
    }

    @Nested
    @DisplayName("expire — planes no-Enterprise")
    class ExpirePlan {

        @Test
        @DisplayName("Marca tenant como expired")
        void setsStatusToExpired() {
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(demoTenant));

            service.checkAndProcess(now);

            assertEquals("expired", demoTenant.status);
            assertEquals(now, demoTenant.updatedAt);
        }

        @Test
        @DisplayName("Expira plan STARTER correctamente")
        void expiresStarterPlan() {
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(starterTenant));

            var result = service.checkAndProcess(now);

            assertEquals(1, result.expired());
            assertEquals("expired", starterTenant.status);
        }

        @Test
        @DisplayName("Expira plan BUSINESS correctamente")
        void expiresBusinessPlan() {
            var businessTenant = createTenant("business", 500);
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(businessTenant));

            var result = service.checkAndProcess(now);

            assertEquals(1, result.expired());
            assertEquals("expired", businessTenant.status);
        }

        @Test
        @DisplayName("Invalida caché Redis del tenant al expirar")
        void invalidatesCache() {
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(demoTenant));

            service.checkAndProcess(now);

            verify(tenantCacheService).invalidate(demoTenant.id, demoTenant.schemaName);
        }

        @Test
        @DisplayName("Dispara webhook plan.expired al expirar")
        void firesExpiredWebhook() {
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(demoTenant));

            service.checkAndProcess(now);

            verify(planAlertService).fireAlert(
                    eq("plan.expired"),
                    eq("https://hooks.example.com/key49"),
                    eq("secret123"),
                    contains("plan.expired"),
                    eq("admin@example.com"),
                    contains("expirado"),
                    anyString());
        }

        @Test
        @DisplayName("No dispara webhook si tenant no tiene webhookUrl")
        void noWebhookIfNoUrl() {
            demoTenant.webhookUrl = null;
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(demoTenant));

            service.checkAndProcess(now);

            verify(planAlertService, never()).fireAlert(
                    anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("No dispara webhook si webhookUrl está en blanco")
        void noWebhookIfBlankUrl() {
            demoTenant.webhookUrl = "  ";
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(demoTenant));

            service.checkAndProcess(now);

            verify(planAlertService, never()).fireAlert(
                    anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("No modifica documentsUsed al expirar")
        void doesNotResetDocumentsUsed() {
            demoTenant.documentsUsed = 20;
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(demoTenant));

            service.checkAndProcess(now);

            assertEquals(20, demoTenant.documentsUsed);
        }

        @Test
        @DisplayName("No crea registro en plan_renewals al expirar")
        void doesNotCreateRenewalRecord() {
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(demoTenant));

            service.checkAndProcess(now);

            verify(renewalRepository, never()).persist(any(PlanRenewal.class));
        }
    }

    @Nested
    @DisplayName("autoRenew — planes Enterprise")
    class AutoRenew {

        @Test
        @DisplayName("Resetea documentsUsed a 0")
        void resetsDocumentsUsed() {
            enterpriseTenant.documentsUsed = 4200;
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(enterpriseTenant));

            service.checkAndProcess(now);

            assertEquals(0, enterpriseTenant.documentsUsed);
        }

        @Test
        @DisplayName("Establece nuevo plan_starts_at")
        void setsNewPlanStartsAt() {
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(enterpriseTenant));

            service.checkAndProcess(now);

            assertEquals(now, enterpriseTenant.planStartsAt);
        }

        @Test
        @DisplayName("Extiende plan_expires_at 30 días")
        void extendsPlanExpiresAt() {
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(enterpriseTenant));

            service.checkAndProcess(now);

            var expected = now.plus(30, ChronoUnit.DAYS);
            assertEquals(expected, enterpriseTenant.planExpiresAt);
        }

        @Test
        @DisplayName("Mantiene status active al auto-renovar")
        void keepsStatusActive() {
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(enterpriseTenant));

            service.checkAndProcess(now);

            assertEquals("active", enterpriseTenant.status);
        }

        @Test
        @DisplayName("Invalida caché Redis del tenant al renovar")
        void invalidatesCache() {
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(enterpriseTenant));

            service.checkAndProcess(now);

            verify(tenantCacheService).invalidate(enterpriseTenant.id, enterpriseTenant.schemaName);
        }

        @Test
        @DisplayName("Crea registro en plan_renewals con historial")
        void createsRenewalRecord() {
            enterpriseTenant.documentsUsed = 3500;
            var previousExpiresAt = enterpriseTenant.planExpiresAt;
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(enterpriseTenant));

            service.checkAndProcess(now);

            var captor = ArgumentCaptor.forClass(PlanRenewal.class);
            verify(renewalRepository).persist(captor.capture());

            var renewal = captor.getValue();
            assertEquals(enterpriseTenant.id, renewal.tenantId);
            assertEquals("enterprise", renewal.planType);
            assertEquals(5000, renewal.documentQuota);
            assertEquals("approved", renewal.status);
            assertEquals("system-auto-renewal", renewal.approvedBy);
            assertEquals(now, renewal.approvedAt);
            assertEquals(now, renewal.createdAt);
            assertTrue(renewal.notes.contains("3500"));
            assertTrue(renewal.notes.contains(previousExpiresAt.toString()));
        }

        @Test
        @DisplayName("Dispara webhook plan.renewed al auto-renovar")
        void firesRenewedWebhook() {
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(enterpriseTenant));

            service.checkAndProcess(now);

            verify(planAlertService).fireAlert(
                    eq("plan.renewed"),
                    eq("https://hooks.example.com/key49"),
                    eq("secret123"),
                    contains("plan.renewed"),
                    eq("admin@example.com"),
                    contains("renovado"),
                    anyString());
        }

        @Test
        @DisplayName("No dispara webhook si tenant no tiene webhookUrl")
        void noWebhookIfNoUrl() {
            enterpriseTenant.webhookUrl = null;
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(enterpriseTenant));

            service.checkAndProcess(now);

            verify(planAlertService, never()).fireAlert(
                    anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Mantiene documentQuota original al renovar")
        void keepsOriginalQuota() {
            enterpriseTenant.documentQuota = 5000;
            when(tenantRepository.findExpiredActive(now))
                    .thenReturn(List.of(enterpriseTenant));

            service.checkAndProcess(now);

            assertEquals(5000, enterpriseTenant.documentQuota);
        }
    }

    @Nested
    @DisplayName("isEnterprise")
    class IsEnterprise {

        @Test
        @DisplayName("Retorna true para plan enterprise")
        void trueForEnterprise() {
            assertTrue(service.isEnterprise(enterpriseTenant));
        }

        @Test
        @DisplayName("Retorna false para plan demo")
        void falseForDemo() {
            assertFalse(service.isEnterprise(demoTenant));
        }

        @Test
        @DisplayName("Retorna false para plan starter")
        void falseForStarter() {
            assertFalse(service.isEnterprise(starterTenant));
        }

        @Test
        @DisplayName("Retorna false para plan business")
        void falseForBusiness() {
            var businessTenant = createTenant("business", 500);
            assertFalse(service.isEnterprise(businessTenant));
        }
    }
}
