package auracore.key49.core.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import auracore.key49.core.model.PlanRenewal;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.PlanRenewalRepository;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.notify.plan.PlanAlertService;

/**
 * Tests unitarios para RenewalAdminService — aprobar/rechazar renovaciones de
 * plan desde el panel de administración.
 */
@ExtendWith(MockitoExtension.class)
class RenewalAdminServiceTest {

    @Mock
    Logger log;

    @Mock
    PlanRenewalRepository renewalRepository;

    @Mock
    TenantRepository tenantRepository;

    @Mock
    TenantCacheService tenantCacheService;

    @Mock
    PlanAlertService planAlertService;

    @InjectMocks
    RenewalAdminService service;

    private UUID renewalId;
    private UUID tenantId;
    private PlanRenewal pendingRenewal;
    private Tenant testTenant;

    @BeforeEach
    void setUp() {
        renewalId = UUID.randomUUID();
        tenantId = UUID.randomUUID();

        pendingRenewal = new PlanRenewal();
        pendingRenewal.id = renewalId;
        pendingRenewal.tenantId = tenantId;
        pendingRenewal.planType = "business";
        pendingRenewal.documentQuota = 500;
        pendingRenewal.amount = new BigDecimal("29.99");
        pendingRenewal.status = "pending";
        pendingRenewal.paymentProofPath = "renewals/tenant-abc/proof-123.pdf";
        pendingRenewal.createdAt = Instant.now().minusSeconds(3600);

        testTenant = new Tenant();
        testTenant.id = tenantId;
        testTenant.ruc = "1790012345001";
        testTenant.legalName = "Empresa Test S.A.";
        testTenant.planType = "starter";
        testTenant.documentQuota = 100;
        testTenant.documentsUsed = 85;
        testTenant.schemaName = "tenant_abc";
        testTenant.webhookUrl = "https://hooks.example.com/key49";
        testTenant.webhookSecret = "secret123";
        testTenant.replyEmail = "admin@example.com";
    }

    @Nested
    @DisplayName("list")
    class ListRenewals {

        @Test
        @DisplayName("Lista todas las renovaciones sin filtro")
        void listsAll() {
            var items = List.of(pendingRenewal);
            when(renewalRepository.findAllPaged(1, 20)).thenReturn(items);
            when(renewalRepository.count()).thenReturn(1L);

            var result = service.list(null, 1, 20);

            assertEquals(1, result.items().size());
            assertEquals(1L, result.total());
            verify(renewalRepository).findAllPaged(1, 20);
            verify(renewalRepository).count();
        }

        @Test
        @DisplayName("Lista renovaciones con filtro de estado vacío")
        void listsAllWithBlankStatus() {
            var items = List.of(pendingRenewal);
            when(renewalRepository.findAllPaged(1, 20)).thenReturn(items);
            when(renewalRepository.count()).thenReturn(1L);

            var result = service.list("  ", 1, 20);

            assertEquals(1, result.items().size());
            verify(renewalRepository).findAllPaged(1, 20);
        }

        @Test
        @DisplayName("Lista renovaciones filtradas por estado")
        void listsByStatus() {
            var items = List.of(pendingRenewal);
            when(renewalRepository.findByStatus("pending", 1, 10)).thenReturn(items);
            when(renewalRepository.countByStatus("pending")).thenReturn(1L);

            var result = service.list("pending", 1, 10);

            assertEquals(1, result.items().size());
            assertEquals(1L, result.total());
            verify(renewalRepository).findByStatus("pending", 1, 10);
        }

        @Test
        @DisplayName("Lista vacía cuando no hay renovaciones")
        void emptyList() {
            when(renewalRepository.findAllPaged(1, 20)).thenReturn(List.of());
            when(renewalRepository.count()).thenReturn(0L);

            var result = service.list(null, 1, 20);

            assertTrue(result.items().isEmpty());
            assertEquals(0L, result.total());
        }
    }

    @Nested
    @DisplayName("getDetail")
    class GetDetail {

        @Test
        @DisplayName("Retorna detalle con renovación y tenant")
        void returnsDetail() {
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            var detail = service.getDetail(renewalId);

            assertNotNull(detail);
            assertEquals(renewalId, detail.renewal().id);
            assertEquals(tenantId, detail.tenant().id);
            assertEquals("Empresa Test S.A.", detail.tenant().legalName);
        }

        @Test
        @DisplayName("Retorna null cuando renovación no existe")
        void returnsNullForMissing() {
            when(renewalRepository.findById(any())).thenReturn(null);

            var detail = service.getDetail(UUID.randomUUID());

            assertNull(detail);
        }
    }

    @Nested
    @DisplayName("approve")
    class ApproveRenewal {

        @Test
        @DisplayName("Aprueba renovación pendiente exitosamente")
        void approvesSuccessfully() {
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            var result = service.approve(renewalId, "admin-user");

            assertTrue(result.success());
            assertNull(result.error());
            assertEquals("approved", result.renewal().status);
            assertEquals("admin-user", result.renewal().approvedBy);
            assertNotNull(result.renewal().approvedAt);
        }

        @Test
        @DisplayName("Actualiza plan del tenant al aprobar")
        void updatesTenantPlan() {
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            service.approve(renewalId, "admin");

            assertEquals("business", testTenant.planType);
            assertEquals(500, testTenant.documentQuota);
            assertEquals(0, testTenant.documentsUsed);
            assertNotNull(testTenant.planStartsAt);
            assertNotNull(testTenant.planExpiresAt);
        }

        @Test
        @DisplayName("Ajusta rate limits según nuevo plan al aprobar")
        void updatesRateLimitsOnApproval() {
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            service.approve(renewalId, "admin");

            // business plan: write=60, read=200
            assertEquals(60, testTenant.rateLimitWriteRpm);
            assertEquals(200, testTenant.rateLimitReadRpm);
            assertEquals(260, testTenant.rateLimitRpm);
        }

        @Test
        @DisplayName("Invalida caché Redis del tenant al aprobar")
        void invalidatesCache() {
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            service.approve(renewalId, "admin");

            verify(tenantCacheService).invalidate(tenantId, "tenant_abc");
        }

        @Test
        @DisplayName("Dispara webhook plan.activated al aprobar")
        void firesWebhook() {
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            service.approve(renewalId, "admin");

            verify(planAlertService).fireAlert(
                    eq("plan.activated"),
                    eq("https://hooks.example.com/key49"),
                    eq("secret123"),
                    contains("plan.activated"),
                    eq("admin@example.com"),
                    contains("Plan activado"),
                    anyString());
        }

        @Test
        @DisplayName("Error cuando renovación no existe")
        void failsForMissingRenewal() {
            when(renewalRepository.findById(any())).thenReturn(null);

            var result = service.approve(UUID.randomUUID(), "admin");

            assertFalse(result.success());
            assertEquals("Renovación no encontrada", result.error());
            assertNull(result.renewal());
        }

        @Test
        @DisplayName("Error cuando renovación no está en estado pending")
        void failsForNonPending() {
            pendingRenewal.status = "approved";
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);

            var result = service.approve(renewalId, "admin");

            assertFalse(result.success());
            assertTrue(result.error().contains("pendiente"));
            assertNull(result.renewal());
        }

        @Test
        @DisplayName("Error cuando tenant no existe")
        void failsForMissingTenant() {
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(null);

            var result = service.approve(renewalId, "admin");

            assertFalse(result.success());
            assertEquals("Tenant no encontrado", result.error());
        }

        @Test
        @DisplayName("No dispara webhook si tenant no tiene webhookUrl")
        void noWebhookIfNoUrl() {
            testTenant.webhookUrl = null;
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            service.approve(renewalId, "admin");

            verify(planAlertService, never()).fireAlert(
                    anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Reinicia documentsUsed a 0 al aprobar")
        void resetsDocumentsUsed() {
            testTenant.documentsUsed = 85;
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            service.approve(renewalId, "admin");

            assertEquals(0, testTenant.documentsUsed);
        }
    }

    @Nested
    @DisplayName("reject")
    class RejectRenewal {

        @Test
        @DisplayName("Rechaza renovación pendiente exitosamente")
        void rejectsSuccessfully() {
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            var result = service.reject(renewalId, "Comprobante ilegible", "admin-user");

            assertTrue(result.success());
            assertNull(result.error());
            assertEquals("rejected", result.renewal().status);
            assertEquals("admin-user", result.renewal().approvedBy);
            assertNotNull(result.renewal().approvedAt);
            assertTrue(result.renewal().notes.contains("Comprobante ilegible"));
        }

        @Test
        @DisplayName("Agrega motivo al campo notes existente")
        void appendsReasonToExistingNotes() {
            pendingRenewal.notes = "Nota previa";
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            var result = service.reject(renewalId, "Monto incorrecto", "admin");

            assertTrue(result.renewal().notes.contains("Nota previa"));
            assertTrue(result.renewal().notes.contains("Monto incorrecto"));
        }

        @Test
        @DisplayName("Dispara webhook plan.rejected al rechazar")
        void firesRejectedWebhook() {
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            service.reject(renewalId, "Comprobante inválido", "admin");

            verify(planAlertService).fireAlert(
                    eq("plan.rejected"),
                    eq("https://hooks.example.com/key49"),
                    eq("secret123"),
                    contains("plan.rejected"),
                    eq("admin@example.com"),
                    contains("rechazada"),
                    anyString());
        }

        @Test
        @DisplayName("Error cuando renovación no existe")
        void failsForMissingRenewal() {
            when(renewalRepository.findById(any())).thenReturn(null);

            var result = service.reject(UUID.randomUUID(), "Razón", "admin");

            assertFalse(result.success());
            assertEquals("Renovación no encontrada", result.error());
        }

        @Test
        @DisplayName("Error cuando renovación no está en estado pending")
        void failsForNonPending() {
            pendingRenewal.status = "rejected";
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);

            var result = service.reject(renewalId, "Razón", "admin");

            assertFalse(result.success());
            assertTrue(result.error().contains("pendiente"));
        }

        @Test
        @DisplayName("No dispara webhook si tenant no tiene webhookUrl")
        void noWebhookIfNoUrl() {
            testTenant.webhookUrl = null;
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            service.reject(renewalId, "Razón", "admin");

            verify(planAlertService, never()).fireAlert(
                    anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("No dispara webhook si tenant es null")
        void noWebhookIfTenantNull() {
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(null);

            var result = service.reject(renewalId, "Razón", "admin");

            assertTrue(result.success());
            verify(planAlertService, never()).fireAlert(
                    anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("No modifica plan del tenant al rechazar")
        void doesNotModifyTenant() {
            when(renewalRepository.findById(renewalId)).thenReturn(pendingRenewal);
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            service.reject(renewalId, "Razón", "admin");

            assertEquals("starter", testTenant.planType);
            assertEquals(100, testTenant.documentQuota);
            assertEquals(85, testTenant.documentsUsed);
        }
    }
}
