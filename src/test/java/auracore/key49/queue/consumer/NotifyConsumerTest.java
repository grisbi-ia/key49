package auracore.key49.queue.consumer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.service.TenantCacheService;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.notify.email.EmailData;
import auracore.key49.notify.email.EmailSendException;
import auracore.key49.notify.email.EmailService;
import auracore.key49.notify.webhook.WebhookDispatcher;
import auracore.key49.queue.mapper.RideDataMapper;
import auracore.key49.storage.DocumentArtifact;
import auracore.key49.storage.ObjectStorageService;
import auracore.key49.storage.StorageException;
import io.vertx.core.json.JsonObject;
import jakarta.persistence.EntityManager;

/**
 * Tests unitarios para NotifyConsumer.
 *
 * <p>
 * Verifica la integración de RIDE, MinIO, email y webhook usando mocks. El
 * patrón usa TenantConnectionManager mockeado que ejecuta el callback
 * directamente.</p>
 */
@ExtendWith(MockitoExtension.class)
class NotifyConsumerTest {

    private static final String TENANT_SCHEMA = "tenant_test";
    private static final String ACCESS_KEY = "1504202501179214673900110010010000000010000000112";
    private static final byte[] RIDE_PDF = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D}; // %PDF-
    private static final byte[] XML_BYTES = "<factura>authorized</factura>".getBytes(StandardCharsets.UTF_8);

    @Mock
    org.jboss.logging.Logger log;

    @Mock
    ConsumerErrorHandler errorHandler;

    @Mock
    TenantConnectionManager connectionManager;

    @Mock
    TenantCacheService tenantCacheService;

    @Mock
    WebhookDispatcher webhookDispatcher;

    @Mock
    RideDataMapper rideDataMapper;

    @Mock
    ObjectStorageService objectStorageService;

    @Mock
    EmailService emailService;

    @Mock
    EntityManager em;

    @InjectMocks
    NotifyConsumer notifyConsumer;

    private Tenant tenant;
    private Document doc;
    private UUID docId;

    @BeforeEach
    void setUp() {
        docId = UUID.randomUUID();
        tenant = createTestTenant();
        doc = createAuthorizedDocument(docId);
    }

    @Nested
    @DisplayName("Flujo completo exitoso")
    class FullSuccessFlow {

        @Test
        @DisplayName("genera RIDE, almacena en MinIO, envía email y transiciona a NOTIFIED")
        void shouldCompleteFullFlow() {
            setupMocks();
            when(rideDataMapper.generateRide(any(Document.class), any(Tenant.class)))
                    .thenReturn(RIDE_PDF);
            when(objectStorageService.store(anyString(), any(LocalDate.class), anyString(),
                    anyString(), eq(DocumentArtifact.AUTHORIZED_XML), any(byte[].class)))
                    .thenReturn("tenant_test/2025/01/01/auth.xml");
            when(objectStorageService.store(anyString(), any(LocalDate.class), anyString(),
                    anyString(), eq(DocumentArtifact.RIDE), any(byte[].class)))
                    .thenReturn("tenant_test/2025/01/01/ride.pdf");

            notifyConsumer.process(buildEvent(docId));

            assertEquals(DocumentStatus.NOTIFIED, doc.status);
            assertEquals("tenant_test/2025/01/01/auth.xml", doc.authorizedXmlPath);
            assertEquals("tenant_test/2025/01/01/ride.pdf", doc.ridePath);
            assertEquals("SENT", doc.emailStatus);
            assertNotNull(doc.emailSentAt);
            assertNotNull(doc.updatedAt);
        }
    }

    @Nested
    @DisplayName("Fallo de RIDE no bloqueante")
    class RideFailure {

        @Test
        @DisplayName("fallo en RIDE no bloquea storage, email, webhook ni transición")
        void shouldContinueWhenRideFails() {
            setupMocks();
            when(rideDataMapper.generateRide(any(Document.class), any(Tenant.class)))
                    .thenThrow(new RuntimeException("Font not found"));
            when(objectStorageService.store(anyString(), any(LocalDate.class), anyString(),
                    anyString(), eq(DocumentArtifact.AUTHORIZED_XML), any(byte[].class)))
                    .thenReturn("path/auth.xml");

            notifyConsumer.process(buildEvent(docId));

            assertEquals(DocumentStatus.NOTIFIED, doc.status);
            // XML should still be stored
            assertEquals("path/auth.xml", doc.authorizedXmlPath);
            // RIDE path remains null since generation failed
            assertNull(doc.ridePath);
            // Email should still be attempted (with null ridePdf)
            verify(emailService).sendDocumentDelivery(any(EmailData.class));
        }
    }

    @Nested
    @DisplayName("Fallo de storage no bloqueante")
    class StorageFailure {

        @Test
        @DisplayName("fallo en MinIO no bloquea email, webhook ni transición")
        void shouldContinueWhenStorageFails() {
            setupMocks();
            when(rideDataMapper.generateRide(any(Document.class), any(Tenant.class)))
                    .thenReturn(RIDE_PDF);
            when(objectStorageService.store(anyString(), any(LocalDate.class), anyString(),
                    anyString(), any(DocumentArtifact.class), any(byte[].class)))
                    .thenThrow(new StorageException("Connection refused", null));

            notifyConsumer.process(buildEvent(docId));

            assertEquals(DocumentStatus.NOTIFIED, doc.status);
            // Paths remain null since storage failed
            assertNull(doc.authorizedXmlPath);
            assertNull(doc.ridePath);
            // Email still attempted
            verify(emailService).sendDocumentDelivery(any(EmailData.class));
        }
    }

    @Nested
    @DisplayName("Fallo de email no bloqueante")
    class EmailFailure {

        @Test
        @DisplayName("fallo en email registra error pero no bloquea webhook ni transición")
        void shouldContinueWhenEmailFails() {
            setupMocks();
            when(rideDataMapper.generateRide(any(Document.class), any(Tenant.class)))
                    .thenReturn(RIDE_PDF);
            when(objectStorageService.store(anyString(), any(LocalDate.class), anyString(),
                    anyString(), eq(DocumentArtifact.AUTHORIZED_XML), any(byte[].class)))
                    .thenReturn("path/auth.xml");
            when(objectStorageService.store(anyString(), any(LocalDate.class), anyString(),
                    anyString(), eq(DocumentArtifact.RIDE), any(byte[].class)))
                    .thenReturn("path/ride.pdf");
            doThrow(new EmailSendException("SMTP error", null))
                    .when(emailService).sendDocumentDelivery(any(EmailData.class));

            notifyConsumer.process(buildEvent(docId));

            assertEquals(DocumentStatus.NOTIFIED, doc.status);
            assertEquals("FAILED", doc.emailStatus);
            assertNotNull(doc.emailError);
            // Storage paths should be set
            assertEquals("path/auth.xml", doc.authorizedXmlPath);
            assertEquals("path/ride.pdf", doc.ridePath);
        }

        @Test
        @DisplayName("error de email se trunca a 500 caracteres")
        void shouldTruncateEmailError() {
            setupMocks();
            when(rideDataMapper.generateRide(any(Document.class), any(Tenant.class)))
                    .thenReturn(RIDE_PDF);
            when(objectStorageService.store(anyString(), any(LocalDate.class), anyString(),
                    anyString(), any(DocumentArtifact.class), any(byte[].class)))
                    .thenReturn("path/file");
            var longMessage = "X".repeat(700);
            doThrow(new EmailSendException(longMessage, null))
                    .when(emailService).sendDocumentDelivery(any(EmailData.class));

            notifyConsumer.process(buildEvent(docId));

            assertEquals(500, doc.emailError.length());
        }
    }

    @Nested
    @DisplayName("Campos actualizados correctamente")
    class FieldUpdates {

        @Test
        @DisplayName("doc.authorizedXmlPath se actualiza con ruta de MinIO")
        void shouldUpdateAuthorizedXmlPath() {
            setupMocks();
            when(rideDataMapper.generateRide(any(), any())).thenReturn(RIDE_PDF);
            when(objectStorageService.store(anyString(), any(LocalDate.class), anyString(),
                    anyString(), eq(DocumentArtifact.AUTHORIZED_XML), any(byte[].class)))
                    .thenReturn("tenant_test/2025/01/01/000000001/authorized.xml");
            when(objectStorageService.store(anyString(), any(LocalDate.class), anyString(),
                    anyString(), eq(DocumentArtifact.RIDE), any(byte[].class)))
                    .thenReturn("tenant_test/2025/01/01/000000001/ride.pdf");

            notifyConsumer.process(buildEvent(docId));

            assertEquals("tenant_test/2025/01/01/000000001/authorized.xml", doc.authorizedXmlPath);
            assertEquals("tenant_test/2025/01/01/000000001/ride.pdf", doc.ridePath);
        }

        @Test
        @DisplayName("email exitoso actualiza emailSentAt y emailStatus")
        void shouldUpdateEmailFieldsOnSuccess() {
            setupMocks();
            when(rideDataMapper.generateRide(any(), any())).thenReturn(RIDE_PDF);
            when(objectStorageService.store(anyString(), any(LocalDate.class), anyString(),
                    anyString(), any(DocumentArtifact.class), any(byte[].class)))
                    .thenReturn("path/file");

            var before = Instant.now();
            notifyConsumer.process(buildEvent(docId));

            assertEquals("SENT", doc.emailStatus);
            assertNotNull(doc.emailSentAt);
            assertNull(doc.emailError);
            // emailSentAt should be recent
            assertTrue(doc.emailSentAt.isAfter(before) || doc.emailSentAt.equals(before));
        }

        @Test
        @DisplayName("documento sin originalXml no intenta guardar XML autorizado")
        void shouldSkipXmlStorageWhenOriginalXmlIsNull() {
            setupMocks();
            doc.originalXml = null;
            when(rideDataMapper.generateRide(any(), any())).thenReturn(RIDE_PDF);
            when(objectStorageService.store(anyString(), any(LocalDate.class), anyString(),
                    anyString(), eq(DocumentArtifact.RIDE), any(byte[].class)))
                    .thenReturn("path/ride.pdf");

            notifyConsumer.process(buildEvent(docId));

            verify(objectStorageService, never()).store(anyString(), any(LocalDate.class),
                    anyString(), anyString(), eq(DocumentArtifact.AUTHORIZED_XML), any(byte[].class));
            assertEquals("path/ride.pdf", doc.ridePath);
        }
    }

    @Nested
    @DisplayName("Casos borde")
    class EdgeCases {

        @Test
        @DisplayName("tenant inexistente no genera excepción")
        void shouldHandleNonExistentTenant() {
            when(tenantCacheService.findBySchemaName("tenant_unknown")).thenReturn(null);

            var event = new JsonObject()
                    .put("document_id", docId.toString())
                    .put("tenant_schema_name", "tenant_unknown")
                    .put("event_type", "doc.notify")
                    .put("retry_count", 0)
                    .put("timestamp", Instant.now().toString());

            notifyConsumer.process(event);

            // No exception thrown, no state change
        }

        @Test
        @DisplayName("documento inexistente no genera excepción")
        void shouldHandleNonExistentDocument() {
            when(tenantCacheService.findBySchemaName(TENANT_SCHEMA)).thenReturn(tenant);
            setupConnectionManager(null);

            notifyConsumer.process(buildEvent(docId));

            // No exception thrown
        }

        @Test
        @DisplayName("documento ya NOTIFIED se ignora")
        void shouldSkipAlreadyNotifiedDocument() {
            doc.status = DocumentStatus.NOTIFIED;

            when(tenantCacheService.findBySchemaName(TENANT_SCHEMA)).thenReturn(tenant);
            setupConnectionManager(doc);

            notifyConsumer.process(buildEvent(docId));

            // Should not attempt RIDE, email, or webhook
            verify(rideDataMapper, never()).generateRide(any(), any());
            verify(emailService, never()).sendDocumentDelivery(any());
        }

        @Test
        @DisplayName("sin recipient email, email se envía sin destinatarios (EmailService maneja)")
        void shouldHandleNoRecipientEmail() {
            setupMocks();
            doc.recipientEmail = null;
            when(rideDataMapper.generateRide(any(), any())).thenReturn(RIDE_PDF);
            when(objectStorageService.store(anyString(), any(LocalDate.class), anyString(),
                    anyString(), any(DocumentArtifact.class), any(byte[].class)))
                    .thenReturn("path/file");

            notifyConsumer.process(buildEvent(docId));

            assertEquals(DocumentStatus.NOTIFIED, doc.status);
            verify(emailService).sendDocumentDelivery(any(EmailData.class));
        }
    }

    // ── Mock setup helpers ──
    private void setupMocks() {
        when(tenantCacheService.findBySchemaName(TENANT_SCHEMA)).thenReturn(tenant);
        setupConnectionManager(doc);
    }

    @SuppressWarnings("unchecked")
    private void setupConnectionManager(Document document) {
        when(connectionManager.withTenantTransaction(eq(TENANT_SCHEMA),
                any(java.util.function.Function.class)))
                .thenAnswer(invocation -> {
                    var callback = (java.util.function.Function<EntityManager, Object>) invocation.getArgument(1);
                    when(em.find(Document.class, docId)).thenReturn(document);
                    return callback.apply(em);
                });
    }

    // ── Test data builders ──
    private JsonObject buildEvent(UUID documentId) {
        return new JsonObject()
                .put("document_id", documentId.toString())
                .put("tenant_schema_name", TENANT_SCHEMA)
                .put("event_type", "doc.notify")
                .put("retry_count", 0)
                .put("timestamp", Instant.now().toString());
    }

    private Document createAuthorizedDocument(UUID id) {
        var d = new Document();
        d.id = id;
        d.documentType = "01";
        d.establishment = "001";
        d.issuePoint = "001";
        d.sequenceNumber = "000000001";
        d.accessKey = ACCESS_KEY;
        d.authorizationNumber = ACCESS_KEY;
        d.authorizationDate = Instant.parse("2025-01-15T20:00:00Z");
        d.status = DocumentStatus.AUTHORIZED;
        d.recipientIdType = "04";
        d.recipientId = "1712345678";
        d.recipientName = "Cliente de Prueba";
        d.recipientEmail = "cliente@test.com";
        d.recipientAddress = "Quito";
        d.issueDate = LocalDate.of(2025, 1, 15);
        d.subtotalBeforeTax = new BigDecimal("50.00");
        d.totalDiscount = BigDecimal.ZERO;
        d.tip = BigDecimal.ZERO;
        d.totalAmount = new BigDecimal("57.50");
        d.vatAmount = new BigDecimal("7.50");
        d.currency = "DOLAR";
        d.originalXml = "<factura>authorized</factura>";
        d.requestPayload = "{}";
        d.createdAt = Instant.now();
        d.updatedAt = Instant.now();
        return d;
    }

    private Tenant createTestTenant() {
        var t = new Tenant();
        t.id = UUID.randomUUID();
        t.ruc = "1792146739001";
        t.legalName = "Test Corp S.A.";
        t.tradeName = "Test Corp";
        t.mainAddress = "Guayaquil";
        t.requiredAccounting = true;
        t.microEnterpriseRegime = false;
        t.environment = "test";
        t.emissionType = 1;
        t.schemaName = TENANT_SCHEMA;
        t.webhookUrl = "https://example.com/webhook";
        t.webhookSecret = "secret123";
        t.status = "active";
        return t;
    }
}
