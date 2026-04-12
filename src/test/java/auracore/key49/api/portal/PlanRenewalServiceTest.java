package auracore.key49.api.portal;

import auracore.key49.core.model.PlanRenewal;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.PlanRenewalRepository;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.notify.plan.PlanAlertService;
import auracore.key49.storage.ObjectStorageService;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests unitarios para PlanRenewalService — solicitud de renovación de plan
 * desde el portal del tenant.
 */
@ExtendWith(MockitoExtension.class)
class PlanRenewalServiceTest {

    @Mock
    Logger log;

    @Mock
    TenantRepository tenantRepository;

    @Mock
    PlanRenewalRepository planRenewalRepository;

    @Mock
    ObjectStorageService storageService;

    @Mock
    PlanAlertService planAlertService;

    @InjectMocks
    PlanRenewalService service;

    @TempDir
    Path tempDir;

    private UUID tenantId;
    private Tenant testTenant;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        testTenant = new Tenant();
        testTenant.id = tenantId;
        testTenant.planType = "demo";
        testTenant.documentQuota = 25;
        testTenant.documentsUsed = 10;
        testTenant.planStartsAt = Instant.now().minusSeconds(86400);
        testTenant.planExpiresAt = Instant.now().plusSeconds(86400 * 30);
        testTenant.legalName = "Empresa Test S.A.";
        testTenant.webhookUrl = "https://hooks.example.com/key49";
        testTenant.webhookSecret = "secret123";
        testTenant.replyEmail = "admin@example.com";
    }

    @Nested
    @DisplayName("getCurrentPlan")
    class GetCurrentPlan {

        @Test
        @DisplayName("Retorna info del plan cuando tenant existe")
        void returnsCurrentPlan() {
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            var result = service.getCurrentPlan(tenantId);

            assertNotNull(result);
            assertEquals("demo", result.planType());
            assertEquals(25, result.documentQuota());
            assertEquals(10, result.documentsUsed());
            assertEquals(40, result.usagePercent());
            assertNotNull(result.planStartsAt());
            assertNotNull(result.planExpiresAt());
        }

        @Test
        @DisplayName("Retorna null cuando tenant no existe")
        void returnsNull_whenTenantNotFound() {
            when(tenantRepository.findById(any())).thenReturn(null);

            var result = service.getCurrentPlan(UUID.randomUUID());

            assertNull(result);
        }

        @Test
        @DisplayName("Calcula 0% cuando cuota es 0")
        void returnsZeroPercent_whenQuotaIsZero() {
            testTenant.documentQuota = 0;
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            var result = service.getCurrentPlan(tenantId);

            assertEquals(0, result.usagePercent());
        }

        @Test
        @DisplayName("Calcula porcentaje con alta utilización")
        void calculatesHighUsage() {
            testTenant.documentsUsed = 24;
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            var result = service.getCurrentPlan(tenantId);

            assertEquals(96, result.usagePercent());
        }
    }

    @Nested
    @DisplayName("requestRenewal")
    class RequestRenewal {

        private FileUpload mockFileUpload(String fileName, String contentType, long size, Path filePath) {
            var upload = mock(FileUpload.class);
            lenient().when(upload.fileName()).thenReturn(fileName);
            lenient().when(upload.contentType()).thenReturn(contentType);
            lenient().when(upload.size()).thenReturn(size);
            lenient().when(upload.filePath()).thenReturn(filePath);
            return upload;
        }

        private Path createTempFile(String name, byte[] content) throws IOException {
            var file = tempDir.resolve(name);
            Files.write(file, content);
            return file;
        }

        @Test
        @DisplayName("Crea solicitud exitosamente con comprobante JPG")
        void createsRenewal_successWithJpg() throws IOException {
            var filePath = createTempFile("receipt.jpg", new byte[]{1, 2, 3});
            var upload = mockFileUpload("receipt.jpg", "image/jpeg", 1024, filePath);

            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);
            when(planRenewalRepository.findPendingByTenantId(tenantId)).thenReturn(List.of());
            when(storageService.storeRaw(anyString(), any(byte[].class), anyString()))
                    .thenReturn("plan-renewals/path");

            var result = service.requestRenewal(tenantId, "starter", upload, "Pago por transferencia");

            assertTrue(result.success());
            assertNull(result.error());
            assertNotNull(result.renewal());
            assertEquals("starter", result.renewal().planType);
            assertEquals(100, result.renewal().documentQuota);
            assertEquals(new BigDecimal("9.99"), result.renewal().amount);
            assertEquals("Pago por transferencia", result.renewal().notes);
            assertEquals("pending", result.renewal().status);

            verify(planRenewalRepository).persist(any(PlanRenewal.class));
            verify(storageService).storeRaw(anyString(), any(byte[].class), eq("image/jpeg"));
        }

        @Test
        @DisplayName("Crea solicitud con comprobante PDF")
        void createsRenewal_successWithPdf() throws IOException {
            var filePath = createTempFile("comprobante.pdf", new byte[]{1, 2, 3});
            var upload = mockFileUpload("comprobante.pdf", "application/pdf", 2048, filePath);

            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);
            when(planRenewalRepository.findPendingByTenantId(tenantId)).thenReturn(List.of());
            when(storageService.storeRaw(anyString(), any(byte[].class), anyString()))
                    .thenReturn("plan-renewals/path");

            var result = service.requestRenewal(tenantId, "business", upload, null);

            assertTrue(result.success());
            assertEquals("business", result.renewal().planType);
            assertEquals(500, result.renewal().documentQuota);
            assertEquals(new BigDecimal("29.99"), result.renewal().amount);
        }

        @Test
        @DisplayName("Rechaza plan no válido")
        void rejectsInvalidPlan() throws IOException {
            var filePath = createTempFile("receipt.jpg", new byte[]{1});
            var upload = mockFileUpload("receipt.jpg", "image/jpeg", 100, filePath);

            var result = service.requestRenewal(tenantId, "premium_gold", upload, null);

            assertFalse(result.success());
            assertEquals("Plan no válido", result.error());
            verifyNoInteractions(planRenewalRepository);
        }

        @Test
        @DisplayName("Rechaza si hay renovación pendiente")
        void rejectsPendingRenewal() throws IOException {
            var filePath = createTempFile("receipt.jpg", new byte[]{1});
            var upload = mockFileUpload("receipt.jpg", "image/jpeg", 100, filePath);
            var pending = new PlanRenewal();
            pending.status = "pending";

            when(planRenewalRepository.findPendingByTenantId(tenantId)).thenReturn(List.of(pending));

            var result = service.requestRenewal(tenantId, "starter", upload, null);

            assertFalse(result.success());
            assertTrue(result.error().contains("pendiente"));
        }

        @Test
        @DisplayName("Rechaza si tenant no existe")
        void rejectsTenantNotFound() throws IOException {
            var filePath = createTempFile("receipt.jpg", new byte[]{1});
            var upload = mockFileUpload("receipt.jpg", "image/jpeg", 100, filePath);

            when(planRenewalRepository.findPendingByTenantId(tenantId)).thenReturn(List.of());
            when(tenantRepository.findById(tenantId)).thenReturn(null);

            var result = service.requestRenewal(tenantId, "starter", upload, null);

            assertFalse(result.success());
            assertEquals("Tenant no encontrado", result.error());
        }

        @Test
        @DisplayName("Rechaza archivo null")
        void rejectsNullFile() {
            when(planRenewalRepository.findPendingByTenantId(tenantId)).thenReturn(List.of());
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            var result = service.requestRenewal(tenantId, "starter", null, null);

            assertFalse(result.success());
            assertEquals("Debe adjuntar un comprobante de pago", result.error());
        }

        @Test
        @DisplayName("Rechaza archivo mayor a 5 MB")
        void rejectsOversizedFile() throws IOException {
            var filePath = createTempFile("big.jpg", new byte[]{1});
            var upload = mockFileUpload("big.jpg", "image/jpeg", 6 * 1024 * 1024, filePath);

            when(planRenewalRepository.findPendingByTenantId(tenantId)).thenReturn(List.of());
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            var result = service.requestRenewal(tenantId, "starter", upload, null);

            assertFalse(result.success());
            assertTrue(result.error().contains("5 MB"));
        }

        @Test
        @DisplayName("Rechaza tipo de archivo no permitido")
        void rejectsDisallowedContentType() throws IOException {
            var filePath = createTempFile("script.exe", new byte[]{1});
            var upload = mockFileUpload("script.exe", "application/octet-stream", 100, filePath);

            when(planRenewalRepository.findPendingByTenantId(tenantId)).thenReturn(List.of());
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            var result = service.requestRenewal(tenantId, "starter", upload, null);

            assertFalse(result.success());
            assertTrue(result.error().contains("no permitido"));
        }

        @Test
        @DisplayName("Rechaza extensión no permitida")
        void rejectsDisallowedExtension() throws IOException {
            var filePath = createTempFile("file.gif", new byte[]{1});
            var upload = mockFileUpload("file.gif", "image/jpeg", 100, filePath);

            when(planRenewalRepository.findPendingByTenantId(tenantId)).thenReturn(List.of());
            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);

            var result = service.requestRenewal(tenantId, "starter", upload, null);

            assertFalse(result.success());
            assertTrue(result.error().contains("extensión") || result.error().contains("no permitida"));
        }

        @Test
        @DisplayName("Dispara webhook plan.renewal_requested")
        void firesWebhook() throws IOException {
            var filePath = createTempFile("receipt.png", new byte[]{1, 2});
            var upload = mockFileUpload("receipt.png", "image/png", 512, filePath);

            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);
            when(planRenewalRepository.findPendingByTenantId(tenantId)).thenReturn(List.of());
            when(storageService.storeRaw(anyString(), any(byte[].class), anyString()))
                    .thenReturn("plan-renewals/path");

            service.requestRenewal(tenantId, "enterprise", upload, "Pago con tarjeta");

            verify(planAlertService).fireAlert(
                    eq("plan.renewal_requested"),
                    eq("https://hooks.example.com/key49"),
                    eq("secret123"),
                    anyString(),
                    eq("admin@example.com"),
                    contains("renovación"),
                    anyString());
        }

        @Test
        @DisplayName("No dispara webhook si tenant no tiene webhook URL")
        void skipsWebhook_whenNoUrl() throws IOException {
            testTenant.webhookUrl = null;
            var filePath = createTempFile("receipt.jpg", new byte[]{1, 2});
            var upload = mockFileUpload("receipt.jpg", "image/jpeg", 512, filePath);

            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);
            when(planRenewalRepository.findPendingByTenantId(tenantId)).thenReturn(List.of());
            when(storageService.storeRaw(anyString(), any(byte[].class), anyString()))
                    .thenReturn("plan-renewals/path");

            var result = service.requestRenewal(tenantId, "starter", upload, null);

            assertTrue(result.success());
            verifyNoInteractions(planAlertService);
        }

        @Test
        @DisplayName("Ruta MinIO sigue patrón plan-renewals/{tenant}/{renewal}.{ext}")
        void minioPathFollowsPattern() throws IOException {
            var filePath = createTempFile("receipt.pdf", new byte[]{1, 2, 3});
            var upload = mockFileUpload("receipt.pdf", "application/pdf", 1024, filePath);

            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);
            when(planRenewalRepository.findPendingByTenantId(tenantId)).thenReturn(List.of());
            when(storageService.storeRaw(anyString(), any(byte[].class), anyString()))
                    .thenReturn("plan-renewals/path");

            service.requestRenewal(tenantId, "starter", upload, null);

            var pathCaptor = ArgumentCaptor.forClass(String.class);
            verify(storageService).storeRaw(pathCaptor.capture(), any(byte[].class), eq("application/pdf"));

            var path = pathCaptor.getValue();
            assertTrue(path.startsWith("plan-renewals/" + tenantId + "/"));
            assertTrue(path.endsWith(".pdf"));
        }

        @Test
        @DisplayName("Maneja error de MinIO")
        void handlesMinioError() throws IOException {
            var filePath = createTempFile("receipt.jpg", new byte[]{1, 2});
            var upload = mockFileUpload("receipt.jpg", "image/jpeg", 512, filePath);

            when(tenantRepository.findById(tenantId)).thenReturn(testTenant);
            when(planRenewalRepository.findPendingByTenantId(tenantId)).thenReturn(List.of());
            when(storageService.storeRaw(anyString(), any(byte[].class), anyString()))
                    .thenThrow(new RuntimeException("MinIO down"));

            var result = service.requestRenewal(tenantId, "starter", upload, null);

            assertFalse(result.success());
            assertTrue(result.error().contains("comprobante"));
        }
    }

    @Nested
    @DisplayName("getFileExtension")
    class GetFileExtension {

        @Test
        @DisplayName("Extrae extensión de nombre de archivo")
        void extractsExtension() {
            assertEquals("pdf", PlanRenewalService.getFileExtension("receipt.pdf"));
            assertEquals("jpg", PlanRenewalService.getFileExtension("photo.JPG"));
            assertEquals("png", PlanRenewalService.getFileExtension("image.png"));
        }

        @Test
        @DisplayName("Retorna vacío sin extensión")
        void returnsEmpty_withoutExtension() {
            assertEquals("", PlanRenewalService.getFileExtension("noext"));
            assertEquals("", PlanRenewalService.getFileExtension(null));
        }

        @Test
        @DisplayName("Maneja múltiples puntos")
        void handlesMultipleDots() {
            assertEquals("pdf", PlanRenewalService.getFileExtension("my.file.name.pdf"));
        }
    }

    @Nested
    @DisplayName("hasPendingRenewal")
    class HasPendingRenewal {

        @Test
        @DisplayName("Retorna true cuando hay renovaciones pendientes")
        void returnsTrue_whenPending() {
            var pending = new PlanRenewal();
            pending.status = "pending";
            when(planRenewalRepository.findPendingByTenantId(tenantId)).thenReturn(List.of(pending));

            assertTrue(service.hasPendingRenewal(tenantId));
        }

        @Test
        @DisplayName("Retorna false cuando no hay pendientes")
        void returnsFalse_whenNoPending() {
            when(planRenewalRepository.findPendingByTenantId(tenantId)).thenReturn(List.of());

            assertFalse(service.hasPendingRenewal(tenantId));
        }
    }

    @Nested
    @DisplayName("availablePlans")
    class AvailablePlans {

        @Test
        @DisplayName("Lista planes disponibles correctamente")
        void listsAvailablePlans() {
            var plans = PlanRenewalService.AVAILABLE_PLANS;

            assertEquals(3, plans.size());
            assertEquals("starter", plans.get(0).code());
            assertEquals(100, plans.get(0).documentQuota());
            assertEquals("business", plans.get(1).code());
            assertEquals(500, plans.get(1).documentQuota());
            assertEquals("enterprise", plans.get(2).code());
            assertEquals(10000, plans.get(2).documentQuota());
        }
    }
}
