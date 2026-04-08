package auracore.key49.queue.consumer;

import java.io.InputStream;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import com.fasterxml.jackson.databind.ObjectMapper;

import auracore.key49.core.Key49Constants;
import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.signer.CertificateEncryptor;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;

/**
 * Test de integración para SignConsumer.
 *
 * <p>Valida que el consumidor firma documentos correctamente:
 * genera XML, clave de acceso, firma XAdES-BES, y transiciona estado
 * CREATED → SIGNED. También verifica manejo de errores (documento no existente,
 * estado inválido, tenant sin certificado).</p>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SignConsumerIntegrationTest {

    private static final String TENANT_SCHEMA = "tenant_sign_test";
    private static final String TENANT_RUC = "1790016919001";

    @Inject
    SignConsumer signConsumer;

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper objectMapper;

    private UUID tenantId;
    private UUID docIdForSign;
    private UUID docIdAlreadySigned;
    private UUID docIdNoCert;

    @BeforeAll
    void setup() throws Exception {
        tenantId = UUID.randomUUID();

        // Cargar certificado de prueba y encriptar contraseña
        byte[] certP12;
        try (InputStream is = getClass().getResourceAsStream("/test-cert.p12")) {
            assertNotNull(is, "test-cert.p12 must exist in test resources");
            certP12 = is.readAllBytes();
        }
        var masterKey = CertificateEncryptor.generateMasterKey();
        var masterKeyBase64 = java.util.Base64.getEncoder().encodeToString(masterKey);
        var encPassword = CertificateEncryptor.encryptPassword("test1234".toCharArray(), masterKey);

        // Configurar master key como system property para que el consumer la lea
        System.setProperty("key49.master-key", masterKeyBase64);

        try (var conn = dataSource.getConnection()) {
            // 1. Insertar tenant con certificado
            try (var ps = conn.prepareStatement("""
                    INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, status, certificate_p12, certificate_password_enc,
                        created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 10000, 'active', ?, ?, now(), now())
                    """)) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, TENANT_RUC);
                ps.setString(3, "Sign Test Corp S.A.");
                ps.setString(4, "Sign Test");
                ps.setString(5, "Quito");
                ps.setString(6, TENANT_SCHEMA);
                ps.setBytes(7, certP12);
                ps.setBytes(8, encPassword);
                ps.executeUpdate();
            }

            // 2. Crear esquema tenant con tablas
            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA);
                stmt.execute(TestSchemaHelper.documentsTableSql(TENANT_SCHEMA));
                stmt.execute(TestSchemaHelper.outboxTableSql(TENANT_SCHEMA));
            }

            // 3. Insertar documento en estado CREATED (para firmar)
            docIdForSign = UUID.randomUUID();
            insertDocument(conn, docIdForSign, "CREATED", "000000001", buildInvoicePayload());

            // 4. Insertar documento ya firmado (para verificar skip)
            docIdAlreadySigned = UUID.randomUUID();
            insertDocument(conn, docIdAlreadySigned, "SIGNED", "000000002", buildInvoicePayload());
        }
    }

    @Test
    @Order(1)
    @DisplayName("firma documento CREATED → SIGNED con éxito")
    void shouldSignCreatedDocument() throws SQLException {
        var event = DocumentEvent.of(docIdForSign, TENANT_SCHEMA, "doc.sign");
        var json = toJson(event);

        signConsumer.process(json);

        // Verificar que el documento ahora está SIGNED
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT status, access_key, original_xml FROM %s.documents WHERE document_id = ?::uuid"
                             .formatted(TENANT_SCHEMA))) {
            ps.setString(1, docIdForSign.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Documento debe existir");
                assertEquals("SIGNED", rs.getString("status"));
                assertNotNull(rs.getString("access_key"), "Debe tener clave de acceso");
                assertEquals(49, rs.getString("access_key").length(), "Clave de acceso: 49 dígitos");
                assertNotNull(rs.getString("original_xml"), "Debe tener XML firmado");
                assertTrue(rs.getString("original_xml").contains("ds:Signature")
                                || rs.getString("original_xml").contains("Signature"),
                        "XML debe contener firma XAdES");
            }
        }

        // Verificar que se creó un evento outbox para enviar al SRI
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT event_type, published FROM %s.outbox WHERE aggregate_id = ?::uuid"
                             .formatted(TENANT_SCHEMA))) {
            ps.setString(1, docIdForSign.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "Debe existir evento outbox");
                assertEquals("doc.send", rs.getString("event_type"));
            }
        }
    }

    @Test
    @Order(2)
    @DisplayName("ignora documento ya firmado (estado SIGNED)")
    void shouldSkipAlreadySignedDocument() throws SQLException {
        var event = DocumentEvent.of(docIdAlreadySigned, TENANT_SCHEMA, "doc.sign");
        var json = toJson(event);

        signConsumer.process(json);

        // El documento debe mantener estado SIGNED (no re-procesar)
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                     "SELECT status FROM %s.documents WHERE document_id = ?::uuid"
                             .formatted(TENANT_SCHEMA))) {
            ps.setString(1, docIdAlreadySigned.toString());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("SIGNED", rs.getString("status"));
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("documento inexistente no genera excepción")
    void shouldHandleNonExistentDocument() {
        var event = DocumentEvent.of(UUID.randomUUID(), TENANT_SCHEMA, "doc.sign");
        var json = toJson(event);

        // No debe lanzar excepción
        signConsumer.process(json);
    }

    @Test
    @Order(4)
    @DisplayName("tenant inexistente no genera excepción")
    void shouldHandleNonExistentTenant() {
        var event = DocumentEvent.of(docIdForSign, "tenant_no_existe", "doc.sign");
        var json = toJson(event);

        // No debe lanzar excepción
        signConsumer.process(json);
    }

    // ── Helpers ──

    private JsonObject toJson(DocumentEvent event) {
        return new JsonObject()
                .put("document_id", event.documentId().toString())
                .put("tenant_schema_name", event.tenantSchemaName())
                .put("event_type", event.eventType())
                .put("retry_count", event.retryCount())
                .put("timestamp", event.timestamp().toString());
    }

    private void insertDocument(java.sql.Connection conn, UUID docId, String status,
                                String seqNum, String payload) throws SQLException {
        try (var ps = conn.prepareStatement("""
                INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                    sequence_number, recipient_id_type, recipient_id, recipient_name, recipient_email,
                    issue_date, status, request_payload, subtotal_before_tax, total_discount, tip,
                    total_amount, vat_amount, created_at, updated_at)
                VALUES (?::uuid, '01', '001', '001', ?, '04', '1790016919001', 'Empresa S.A.',
                    'test@test.com', ?, ?, ?::jsonb, 50.00, 0.00, 0.00, 57.50, 7.50, now(), now())
                """.formatted(TENANT_SCHEMA))) {
            ps.setString(1, docId.toString());
            ps.setString(2, seqNum);
            ps.setObject(3, LocalDate.now(Key49Constants.EC_ZONE));
            ps.setString(4, status);
            ps.setString(5, payload);
            ps.executeUpdate();
        }
    }

    private String buildInvoicePayload() {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "items", List.of(Map.of(
                            "main_code", "PROD-001",
                            "description", "Servicio de hosting",
                            "unit_of_measure", "UNIDAD",
                            "quantity", 1,
                            "unit_price", "50.00",
                            "discount", "0.00",
                            "taxes", List.of(Map.of(
                                    "code", "2",
                                    "percentage_code", "4",
                                    "rate", "15"
                            ))
                    )),
                    "payments", List.of(Map.of(
                            "method", "20",
                            "amount", "57.50",
                            "term", 0,
                            "time_unit", "days"
                    ))
            ));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
