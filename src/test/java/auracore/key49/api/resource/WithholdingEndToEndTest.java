package auracore.key49.api.resource;

import auracore.key49.core.Key49Constants;
import auracore.key49.core.service.ApiKeyService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Test end-to-end del flujo completo de comprobante de retención electrónico
 * vía REST API.
 *
 * <p>
 * Crea un tenant con esquema en PostgreSQL, genera API key, y ejercita todos
 * los endpoints de /v1/withholdings: creación, consulta, listado, idempotencia,
 * validaciones y anulación.</p>
 */

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WithholdingEndToEndTest {

    private static final String TENANT_SCHEMA = "tenant_e2e_wh";
    private static final String TENANT_RUC = "1790016919001";

    @Inject
    javax.sql.DataSource dataSource;

    private String rawApiKey;
    private UUID tenantId;
    private String createdDocumentId;

    @BeforeAll
    void setupTenantAndApiKey() throws SQLException {
        tenantId = UUID.randomUUID();
        var generated = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        rawApiKey = generated.rawKey();

        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("""
INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                            required_accounting, micro_enterprise_regime, environment,
                            emission_type, rate_limit_rpm, status, created_at, updated_at)
                        VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 10000, 'active', now(), now())
            """)) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, TENANT_RUC);
                ps.setString(3, "E2E Withholding Corp S.A.");
                ps.setString(4, "E2E WH");
                ps.setString(5, "Quito");
                ps.setString(6, TENANT_SCHEMA);
                ps.executeUpdate();
            }

            try (var ps2 = conn.prepareStatement("""
                    INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status, created_at)
                    VALUES (?::uuid, ?::uuid, ?, ?, ?, '*', 'active', now())""")) {
                ps2.setObject(1, UUID.randomUUID().toString());
                ps2.setObject(2, tenantId.toString());
                ps2.setString(3, generated.keyPrefix());
                ps2.setString(4, generated.hash());
                ps2.setString(5, "e2e-wh-key");
                ps2.executeUpdate();
            }

            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA);
                stmt.execute("""
        CREATE TABLE IF NOT EXISTS %s.documents (
                            document_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            document_type VARCHAR(2) NOT NULL,
                            establishment VARCHAR(3) NOT NULL,
                            issue_point VARCHAR(3) NOT NULL,
                            sequence_number VARCHAR(9) NOT NULL,
                            access_key VARCHAR(49),
                            authorization_number VARCHAR(49),
                            request_origin VARCHAR(10) NOT NULL DEFAULT 'JSON',
                            recipient_id_type VARCHAR(2) NOT NULL,
                            recipient_id VARCHAR(20) NOT NULL,
                            recipient_name VARCHAR(300) NOT NULL,
                            recipient_email VARCHAR(500),
                            recipient_address VARCHAR(300),
                            recipient_phone VARCHAR(50),
                            subtotal_before_tax NUMERIC(14,2) NOT NULL DEFAULT 0,
                            total_discount NUMERIC(14,2) NOT NULL DEFAULT 0,
                            subtotal_vat_0 NUMERIC(14,2) NOT NULL DEFAULT 0,
                            subtotal_vat_12 NUMERIC(14,2) NOT NULL DEFAULT 0,
                            subtotal_vat_15 NUMERIC(14,2) NOT NULL DEFAULT 0,
                            subtotal_non_taxable NUMERIC(14,2) NOT NULL DEFAULT 0,
                            subtotal_exempt NUMERIC(14,2) NOT NULL DEFAULT 0,
                            vat_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
                            ice_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
                            tip NUMERIC(14,2) NOT NULL DEFAULT 0,
                            total_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
                            currency VARCHAR(15) NOT NULL DEFAULT 'DOLAR',
                            issue_date DATE NOT NULL,
                            authorization_date TIMESTAMPTZ,
                            sri_submission_date TIMESTAMPTZ,
                            status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
                            retry_count SMALLINT NOT NULL DEFAULT 0,
                            max_retries SMALLINT NOT NULL DEFAULT 6,
                            next_retry_at TIMESTAMPTZ,
                            last_error_code VARCHAR(10),
                            last_error_message TEXT,
                            sri_messages JSONB,
                            unsigned_xml_path VARCHAR(500),
                            signed_xml_path VARCHAR(500),
                            authorized_xml_path VARCHAR(500),
                            ride_path VARCHAR(500),
                            request_payload JSONB,
                            original_xml TEXT,
                            request_ip VARCHAR(45),
                            idempotency_key VARCHAR(50),
                            email_sent_at TIMESTAMPTZ,
                            email_status VARCHAR(20),
                            email_error VARCHAR(500),
                            voided_at TIMESTAMPTZ,
                            void_reason VARCHAR(500),
                            version INT NOT NULL DEFAULT 1,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CONSTRAINT uq_wh_documents_number UNIQUE (document_type, establishment, issue_point, sequence_number),
                            CONSTRAINT uq_wh_documents_access_key UNIQUE (access_key),
                            CONSTRAINT uq_wh_documents_idempotency UNIQUE (idempotency_key)
                        )
                        """.formatted(TENANT_SCHEMA));
                stmt.execute("""
        CREATE TABLE IF NOT EXISTS %s.outbox (
                            outbox_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            aggregate_type VARCHAR(50) NOT NULL,
                            aggregate_id UUID NOT NULL,
                            event_type VARCHAR(50) NOT NULL,
                            payload JSONB NOT NULL,
                            published BOOLEAN NOT NULL DEFAULT false,
                            published_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                        """.formatted(TENANT_SCHEMA));
            }
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    private String authHeader() {
        return "Bearer " + rawApiKey;
    }

    private LocalDate today() {
        return LocalDate.now(Key49Constants.EC_ZONE);
    }

    private String currentFiscalPeriod() {
        var now = today();
        return String.format("%02d/%04d", now.getMonthValue(), now.getYear());
    }

    // ── 1. Crear retención exitosamente ──
    @Test
    @Order(1)
    void shouldCreateWithholding_returns202() {
        var body = buildValidRequest("000000001");

        createdDocumentId = RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "idem-wh-001")
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/withholdings")
                .then()
                .statusCode(202)
                .header("X-Request-Id", startsWith("req_"))
                .body("data.id", notNullValue())
                .body("data.status", equalTo("CREATED"))
                .body("data.establishment", equalTo("001"))
                .body("data.issue_point", equalTo("001"))
                .body("data.sequence_number", equalTo("000000001"))
                .body("data.subject.id", equalTo("1790016919001"))
                .body("data.subject.name", equalTo("Proveedor Nacional S.A."))
                .body("meta.request_id", notNullValue())
                .body("meta.timestamp", notNullValue())
                .extract()
                .<String>path("data.id");
    }

    // ── 2. Idempotencia ──
    @Test
    @Order(2)
    void shouldReturnSameDocument_whenIdempotencyKeyRepeated() {
        var body = buildValidRequest("000000099");

        var returnedId = RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "idem-wh-001")
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/withholdings")
                .then()
                .statusCode(202)
                .extract()
                .<String>path("data.id");

        org.junit.jupiter.api.Assertions.assertEquals(createdDocumentId, returnedId);
    }

    // ── 3. Consultar por ID ──
    @Test
    @Order(3)
    void shouldGetWithholdingById() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/withholdings/" + createdDocumentId)
                .then()
                .statusCode(200)
                .body("data.id", equalTo(createdDocumentId))
                .body("data.status", equalTo("CREATED"))
                .body("data.establishment", equalTo("001"))
                .body("data.issue_point", equalTo("001"))
                .body("data.sequence_number", equalTo("000000001"))
                .body("data.subject.id", equalTo("1790016919001"))
                .body("data.issue_date", equalTo(today().toString()));
    }

    // ── 4. Listar retenciones ──
    @Test
    @Order(4)
    void shouldListWithholdings() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/withholdings")
                .then()
                .statusCode(200)
                .body("data", hasSize(greaterThanOrEqualTo(1)))
                .body("meta.total", greaterThanOrEqualTo(1))
                .body("meta.page", equalTo(1))
                .body("meta.per_page", equalTo(20));
    }

    @Test
    @Order(5)
    void shouldListWithholdings_withStatusFilter() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("status", "CREATED")
                .when()
                .get("/v1/withholdings")
                .then()
                .statusCode(200)
                .body("data", hasSize(greaterThanOrEqualTo(1)))
                .body("data[0].status", equalTo("CREATED"));
    }

    @Test
    @Order(6)
    void shouldListWithholdings_withDateFilter() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("date_from", today().toString())
                .queryParam("date_to", today().toString())
                .when()
                .get("/v1/withholdings")
                .then()
                .statusCode(200)
                .body("data", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(7)
    void shouldListWithholdings_withRecipientIdFilter() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("recipient_id", "1790016919001")
                .when()
                .get("/v1/withholdings")
                .then()
                .statusCode(200)
                .body("data", hasSize(greaterThanOrEqualTo(1)));
    }

    // ── 5. Documento duplicado ──
    @Test
    @Order(8)
    void shouldRejectDuplicateDocument() {
        var body = buildValidRequest("000000001");

        RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "idem-wh-002-different")
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/withholdings")
                .then()
                .statusCode(409)
                .body("error.code", equalTo("DUPLICATE_DOCUMENT"));
    }

    // ── 6. Validaciones de request ──
    @Test
    @Order(9)
    void shouldRejectInvalidEstablishment() {
        var body = buildValidRequest("000000010");
        body.put("establishment", "1");

        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/withholdings")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.details", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(10)
    void shouldRejectMissingSubject() {
        var body = buildValidRequest("000000011");
        body.remove("subject");

        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/withholdings")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    @Order(11)
    void shouldRejectMissingSupportingDocuments() {
        var body = buildValidRequest("000000012");
        body.put("supporting_documents", List.of());

        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/withholdings")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    @Order(12)
    void shouldRejectWrongIssueDate() {
        var body = buildValidRequest("000000013");
        body.put("issue_date", "2020-01-01");

        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/withholdings")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    @Order(13)
    void shouldRejectInvalidFiscalPeriod() {
        var body = buildValidRequest("000000014");
        body.put("fiscal_period", "99/2026");

        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/withholdings")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    // ── 7. XML no disponible ──
    @Test
    @Order(14)
    void shouldReturn404_whenXmlNotAvailable() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/withholdings/" + createdDocumentId + "/xml")
                .then()
                .statusCode(404)
                .body("error.code", equalTo("DOCUMENT_NOT_FOUND"));
    }

    // ── 8. RIDE no disponible ──
    @Test
    @Order(15)
    void shouldReturn404_whenRideNotAvailable() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/withholdings/" + createdDocumentId + "/ride")
                .then()
                .statusCode(404)
                .body("error.code", equalTo("DOCUMENT_NOT_FOUND"));
    }

    // ── 9. Anulación: no se puede anular CREATED ──
    @Test
    @Order(16)
    void shouldRejectVoid_whenStatusIsCreated() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(Map.of("reason", "Test void"))
                .when()
                .post("/v1/withholdings/" + createdDocumentId + "/void")
                .then()
                .statusCode(409)
                .body("error.code", equalTo("INVALID_STATE_TRANSITION"));
    }

    // ── 10. Anulación exitosa de documento AUTHORIZED ──
    @Test
    @Order(17)
    void shouldVoidAuthorizedDocument() {
        var body = buildValidRequest("000000002");
        var docId = RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "idem-wh-void")
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/withholdings")
                .then()
                .statusCode(202)
                .extract()
                .<String>path("data.id");

        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                "UPDATE %s.documents SET status = 'AUTHORIZED', access_key = ? WHERE document_id = ?::uuid"
                        .formatted(TENANT_SCHEMA))) {
            ps.setString(1, "0504202607179001691900110010010000000021234567814");
            ps.setString(2, docId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }

        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(Map.of("reason", "Error en datos de retención"))
                .when()
                .post("/v1/withholdings/" + docId + "/void")
                .then()
                .statusCode(200)
                .body("data.status", equalTo("VOIDED"))
                .body("data.void_reason", equalTo("Error en datos de retención"))
                .body("data.voided_at", notNullValue());
    }

    // ── 11. Void sin razón ──
    @Test
    @Order(18)
    void shouldRejectVoid_withoutReason() {
        var body = buildValidRequest("000000003");
        var docId = RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "idem-wh-void-no-reason")
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/withholdings")
                .then()
                .statusCode(202)
                .extract()
                .<String>path("data.id");

        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement(
                "UPDATE %s.documents SET status = 'AUTHORIZED', access_key = ? WHERE document_id = ?::uuid"
                        .formatted(TENANT_SCHEMA))) {
            ps.setString(1, "0504202607179001691900110010010000000031234567817");
            ps.setString(2, docId);
            ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }

        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(Map.of())
                .when()
                .post("/v1/withholdings/" + docId + "/void")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    // ── 12. Crear segundo documento y verificar listado ──
    @Test
    @Order(19)
    void shouldCreateSecondWithholding_andListMultiple() {
        var body = buildValidRequest("000000004");

        RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "idem-wh-004")
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/withholdings")
                .then()
                .statusCode(202);

        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/withholdings")
                .then()
                .statusCode(200)
                .body("data", hasSize(greaterThanOrEqualTo(2)))
                .body("meta.total", greaterThanOrEqualTo(2));
    }

    // ── 13. Consulta inexistente ──
    @Test
    @Order(20)
    void shouldReturn404_forNonExistentDocument() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/withholdings/" + UUID.randomUUID())
                .then()
                .statusCode(404)
                .body("error.code", equalTo("DOCUMENT_NOT_FOUND"));
    }

    // ── 14. Autenticación requerida ──
    @Test
    @Order(21)
    void shouldRequireAuthentication() {
        RestAssured.given()
                .when()
                .get("/v1/withholdings")
                .then()
                .statusCode(401);
    }

    // ── 15. Paginación ──
    @Test
    @Order(22)
    void shouldPaginate() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("page", 1)
                .queryParam("per_page", 1)
                .when()
                .get("/v1/withholdings")
                .then()
                .statusCode(200)
                .body("data", hasSize(1))
                .body("meta.page", equalTo(1))
                .body("meta.per_page", equalTo(1))
                .body("meta.total_pages", greaterThanOrEqualTo(1));
    }

    // ── 16. Resend email en estado CREATED falla ──
    @Test
    @Order(23)
    void shouldRejectResendEmail_whenStatusIsCreated() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .when()
                .post("/v1/withholdings/" + createdDocumentId + "/resend-email")
                .then()
                .statusCode(409);
    }

    // ── 17. Verificar outbox event generado ──
    @Test
    @Order(24)
    void shouldHaveOutboxEvents() {
        int count = 0;
            boolean hasSignEvent = false;
            try (var conn = dataSource.getConnection();
                 var stmt = conn.createStatement();
                 var rs = stmt.executeQuery(
                    "SELECT event_type, published FROM %s.outbox ORDER BY created_at"
                            .formatted(TENANT_SCHEMA))) {
                while (rs.next()) {
                    count++;
                    if ("doc.sign".equals(rs.getString("event_type"))) {
                        hasSignEvent = true;
                    }
                }
            } catch (SQLException e) { throw new RuntimeException(e); }
        org.junit.jupiter.api.Assertions.assertTrue(count > 0, "Should have outbox events");
        org.junit.jupiter.api.Assertions.assertTrue(hasSignEvent, "Should have doc.sign event");
    }

    // ── Helpers ──
    private Map<String, Object> buildValidRequest(String sequenceNumber) {
        var subject = Map.of(
                "id_type", "04",
                "id", "1790016919001",
                "name", "Proveedor Nacional S.A.",
                "subject_type", "01",
                "email", "retencion@proveedor.com",
                "phone", "0991234567");

        var withholding = Map.of(
                "code", "1",
                "retention_code", "312",
                "taxable_base", 1000.00,
                "retention_rate", 1.00,
                "retained_amount", 10.00);

        var supportDocTax = Map.of(
                "tax_code", "2",
                "rate_code", "2",
                "taxable_base", 1000.00,
                "rate", 12.00,
                "amount", 120.00);

        var payment = Map.of(
                "payment_method", "01",
                "total", 1120.00);

        var supportingDoc = new java.util.LinkedHashMap<String, Object>();
        supportingDoc.put("support_code", "01");
        supportingDoc.put("document_code", "01");
        supportingDoc.put("document_number", "001-001-000000001");
        supportingDoc.put("issue_date", today().toString());
        supportingDoc.put("payment_locality", "01");
        supportingDoc.put("total_without_tax", 1000.00);
        supportingDoc.put("total_amount", 1120.00);
        supportingDoc.put("taxes", List.of(supportDocTax));
        supportingDoc.put("withholdings", List.of(withholding));
        supportingDoc.put("payments", List.of(payment));

        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("establishment", "001");
        map.put("issue_point", "001");
        map.put("sequence_number", sequenceNumber);
        map.put("issue_date", today().toString());
        map.put("subject", subject);
        map.put("fiscal_period", currentFiscalPeriod());
        map.put("related_party", false);
        map.put("supporting_documents", List.of(supportingDoc));
        map.put("additional_info", Map.of("Referencia", "Compra 2026-001"));
        return map;
    }
}
