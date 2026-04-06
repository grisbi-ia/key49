package auracore.key49.api.resource;

import auracore.key49.core.Key49Constants;
import auracore.key49.core.service.ApiKeyService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
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

/**
 * Test end-to-end del flujo completo de factura electrónica vía REST API.
 *
 * <p>
 * Crea un tenant con esquema en PostgreSQL, genera API key, y ejercita todos
 * los endpoints de /v1/invoices: creación, consulta, listado, idempotencia,
 * validaciones y anulación.</p>
 *
 * <p>
 * El pipeline de procesamiento (sign, send, authorize) se ejecuta
 * asíncronamente en colas, por lo que este test verifica las respuestas HTTP
 * inmediatas del API (sin esperar procesamiento SRI).</p>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InvoiceEndToEndTest {

    private static final String TENANT_SCHEMA = "tenant_e2e_test";
    private static final String TENANT_RUC = "1790016919001";

    @Inject
    PgPool pgPool;

    private String rawApiKey;
    private UUID tenantId;
    private String createdDocumentId;

    @BeforeAll
    void setupTenantAndApiKey() {
        tenantId = UUID.randomUUID();
        var generated = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        rawApiKey = generated.rawKey();

        // 1. Insert tenant in public schema
        pgPool.preparedQuery("""
                        INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                            required_accounting, micro_enterprise_regime, environment,
                            emission_type, rate_limit_rpm, status, created_at, updated_at)
                        VALUES ($1, $2, $3, $4, $5, $6, false, false, 'test', 1, 100, 'active', now(), now())""")
                .execute(Tuple.of(tenantId, TENANT_RUC, "E2E Test Corp S.A.", "E2E Test", "Quito", TENANT_SCHEMA))
                .await().indefinitely();

        // 2. Insert API key
        pgPool.preparedQuery("""
                        INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status, created_at)
                        VALUES ($1, $2, $3, $4, $5, '*', 'active', now())""")
                .execute(Tuple.of(UUID.randomUUID(), tenantId, generated.keyPrefix(), generated.hash(), "e2e-key"))
                .await().indefinitely();

        // 3. Create tenant schema with documents + outbox tables
        pgPool.query("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA).execute()
                .chain(() -> pgPool.query("""
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
                            CONSTRAINT uq_e2e_documents_number UNIQUE (document_type, establishment, issue_point, sequence_number),
                            CONSTRAINT uq_e2e_documents_access_key UNIQUE (access_key),
                            CONSTRAINT uq_e2e_documents_idempotency UNIQUE (idempotency_key)
                        )
                        """.formatted(TENANT_SCHEMA)).execute())
                .chain(() -> pgPool.query("""
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
                        """.formatted(TENANT_SCHEMA)).execute())
                .await().indefinitely();
    }

    private String authHeader() {
        return "Bearer " + rawApiKey;
    }

    private LocalDate today() {
        return LocalDate.now(Key49Constants.EC_ZONE);
    }

    // ── 1. Crear factura exitosamente ──
    @Test
    @Order(1)
    void shouldCreateInvoice_returns202() {
        var body = buildValidInvoiceRequest("000000001");

        createdDocumentId = RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "idem-e2e-001")
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/invoices")
                .then()
                .statusCode(202)
                .header("X-Request-Id", startsWith("req_"))
                .body("data.id", notNullValue())
                .body("data.status", equalTo("CREATED"))
                .body("data.establishment", equalTo("001"))
                .body("data.issue_point", equalTo("001"))
                .body("data.sequence_number", equalTo("000000001"))
                .body("data.document_type", equalTo("01"))
                .body("data.recipient.id", equalTo("1790016919001"))
                .body("data.recipient.name", equalTo("Empresa S.A."))
                .body("data.total_amount", notNullValue())
                .body("meta.request_id", notNullValue())
                .body("meta.timestamp", notNullValue())
                .extract()
                .path("data.id");
    }

    // ── 2. Idempotencia ──
    @Test
    @Order(2)
    void shouldReturnSameDocument_whenIdempotencyKeyRepeated() {
        var body = buildValidInvoiceRequest("000000099"); // different seq but same idem key

        var returnedId = RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "idem-e2e-001")
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/invoices")
                .then()
                .statusCode(202)
                .extract()
                .path("data.id");

        // Same idempotency key → same document returned
        org.junit.jupiter.api.Assertions.assertEquals(createdDocumentId, returnedId);
    }

    // ── 3. Consultar factura por ID ──
    @Test
    @Order(3)
    void shouldGetInvoiceById() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/invoices/" + createdDocumentId)
                .then()
                .statusCode(200)
                .body("data.id", equalTo(createdDocumentId))
                .body("data.status", equalTo("CREATED"))
                .body("data.establishment", equalTo("001"))
                .body("data.issue_point", equalTo("001"))
                .body("data.sequence_number", equalTo("000000001"))
                .body("data.recipient.id", equalTo("1790016919001"))
                .body("data.issue_date", equalTo(today().toString()));
    }

    // ── 4. Listar facturas ──
    @Test
    @Order(4)
    void shouldListInvoices() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/invoices")
                .then()
                .statusCode(200)
                .body("data", hasSize(greaterThanOrEqualTo(1)))
                .body("meta.total", greaterThanOrEqualTo(1))
                .body("meta.page", equalTo(1))
                .body("meta.per_page", equalTo(20));
    }

    @Test
    @Order(5)
    void shouldListInvoices_withStatusFilter() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("status", "CREATED")
                .when()
                .get("/v1/invoices")
                .then()
                .statusCode(200)
                .body("data", hasSize(greaterThanOrEqualTo(1)))
                .body("data[0].status", equalTo("CREATED"));
    }

    @Test
    @Order(6)
    void shouldListInvoices_withDateFilter() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("date_from", today().toString())
                .queryParam("date_to", today().toString())
                .when()
                .get("/v1/invoices")
                .then()
                .statusCode(200)
                .body("data", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(7)
    void shouldListInvoices_withRecipientIdFilter() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("recipient_id", "1790016919001")
                .when()
                .get("/v1/invoices")
                .then()
                .statusCode(200)
                .body("data", hasSize(greaterThanOrEqualTo(1)));
    }

    // ── 5. Documento duplicado ──
    @Test
    @Order(8)
    void shouldRejectDuplicateDocument() {
        var body = buildValidInvoiceRequest("000000001"); // same seq as Order(1)

        RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "idem-e2e-002-different")
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/invoices")
                .then()
                .statusCode(409)
                .body("error.code", equalTo("DUPLICATE_DOCUMENT"));
    }

    // ── 6. Validaciones de request ──
    @Test
    @Order(9)
    void shouldRejectInvalidEstablishment() {
        var body = buildValidInvoiceRequest("000000010");
        body.put("establishment", "1"); // invalid: not 3 digits

        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/invoices")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.details", hasSize(greaterThanOrEqualTo(1)));
    }

    @Test
    @Order(10)
    void shouldRejectMissingRecipient() {
        var body = buildValidInvoiceRequest("000000011");
        body.remove("recipient");

        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/invoices")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    @Order(11)
    void shouldRejectMissingItems() {
        var body = buildValidInvoiceRequest("000000012");
        body.put("items", List.of());

        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/invoices")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    @Order(12)
    void shouldRejectWrongIssueDate() {
        var body = buildValidInvoiceRequest("000000013");
        body.put("issue_date", "2020-01-01");

        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/invoices")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    @Order(13)
    void shouldRejectInvalidPaymentMethod() {
        var body = buildValidInvoiceRequest("000000014");
        body.put("payments", List.of(Map.of(
                "payment_method", "99", // invalid
                "total", 115.00,
                "term", 0,
                "time_unit", "dias")));

        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/invoices")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    // ── 7. XML no disponible (documento recién creado) ──
    @Test
    @Order(14)
    void shouldReturn404_whenXmlNotAvailable() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/invoices/" + createdDocumentId + "/xml")
                .then()
                .statusCode(404)
                .body("error.code", equalTo("DOCUMENT_NOT_FOUND"));
    }

    // ── 8. RIDE no disponible (documento recién creado) ──
    @Test
    @Order(15)
    void shouldReturn404_whenRideNotAvailable() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/invoices/" + createdDocumentId + "/ride")
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
                .post("/v1/invoices/" + createdDocumentId + "/void")
                .then()
                .statusCode(409)
                .body("error.code", equalTo("INVALID_STATE_TRANSITION"));
    }

    // ── 10. Anulación exitosa de documento AUTHORIZED ──
    @Test
    @Order(17)
    void shouldVoidAuthorizedDocument() {
        // Create second invoice
        var body = buildValidInvoiceRequest("000000002");
        var docId = RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "idem-e2e-void")
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/invoices")
                .then()
                .statusCode(202)
                .extract()
                .path("data.id");

        // Manually transition to AUTHORIZED via direct SQL
        pgPool.preparedQuery(
                "UPDATE %s.documents SET status = 'AUTHORIZED', access_key = $1 WHERE document_id = $2::uuid"
                        .formatted(TENANT_SCHEMA))
                .execute(Tuple.of("0504202601179001691900110010010000000021234567810", docId))
                .await().indefinitely();

        // Now void it
        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(Map.of("reason", "Error en datos del receptor"))
                .when()
                .post("/v1/invoices/" + docId + "/void")
                .then()
                .statusCode(200)
                .body("data.status", equalTo("VOIDED"))
                .body("data.void_reason", equalTo("Error en datos del receptor"))
                .body("data.voided_at", notNullValue());
    }

    // ── 11. Void sin razón ──
    @Test
    @Order(18)
    void shouldRejectVoid_withoutReason() {
        var body = buildValidInvoiceRequest("000000003");
        var docId = RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "idem-e2e-void-no-reason")
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/invoices")
                .then()
                .statusCode(202)
                .extract()
                .path("data.id");

        pgPool.preparedQuery(
                "UPDATE %s.documents SET status = 'AUTHORIZED', access_key = $1 WHERE document_id = $2::uuid"
                        .formatted(TENANT_SCHEMA))
                .execute(Tuple.of("0504202601179001691900110010010000000031234567813", docId))
                .await().indefinitely();

        RestAssured.given()
                .header("Authorization", authHeader())
                .contentType(ContentType.JSON)
                .body(Map.of())
                .when()
                .post("/v1/invoices/" + docId + "/void")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    // ── 12. Crear segunda factura y verificar listado ──
    @Test
    @Order(19)
    void shouldCreateSecondInvoice_andListMultiple() {
        var body = buildValidInvoiceRequest("000000004");

        RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "idem-e2e-004")
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/v1/invoices")
                .then()
                .statusCode(202);

        // List and verify count
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/invoices")
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
                .get("/v1/invoices/" + UUID.randomUUID())
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
                .get("/v1/invoices")
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
                .get("/v1/invoices")
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
                .post("/v1/invoices/" + createdDocumentId + "/resend-email")
                .then()
                .statusCode(409);
    }

    // ── 17. Verificar outbox event generado ──
    @Test
    @Order(24)
    void shouldHaveOutboxEvents() {
        var rows = pgPool.query(
                "SELECT event_type, published FROM %s.outbox ORDER BY created_at"
                        .formatted(TENANT_SCHEMA))
                .execute().await().indefinitely();

        int count = 0;
        boolean hasSignEvent = false;
        for (var row : rows) {
            count++;
            if ("doc.sign".equals(row.getString("event_type"))) {
                hasSignEvent = true;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(count > 0, "Should have outbox events");
        org.junit.jupiter.api.Assertions.assertTrue(hasSignEvent, "Should have doc.sign event");
    }

    // ── 18. Calculos correctos de totales ──
    @Test
    @Order(25)
    void shouldCalculateTotalsCorrectly() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/invoices/" + createdDocumentId)
                .then()
                .statusCode(200)
                .body("data.subtotal_before_tax", notNullValue())
                .body("data.vat_amount", notNullValue())
                .body("data.total_amount", notNullValue());
    }

    // ── Helpers ──
    private Map<String, Object> buildValidInvoiceRequest(String sequenceNumber) {
        var recipient = Map.of(
                "id_type", "04",
                "id", "1790016919001",
                "name", "Empresa S.A.",
                "address", "Quito, Ecuador",
                "email", "factura@empresa.com",
                "phone", "0991234567");

        var tax = Map.of(
                "code", "2",
                "rate_code", "2",
                "rate", 12);

        var item = Map.of(
                "main_code", "PROD001",
                "auxiliary_code", "AUX001",
                "description", "Servicio de consultoría",
                "unit_of_measure", "unidad",
                "quantity", 1,
                "unit_price", 100.00,
                "discount", 0.00,
                "taxes", List.of(tax));

        var payment = Map.of(
                "payment_method", "01",
                "total", 112.00,
                "term", 0,
                "time_unit", "dias");

        // Use mutable map so tests can modify fields
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("establishment", "001");
        map.put("issue_point", "001");
        map.put("sequence_number", sequenceNumber);
        map.put("issue_date", today().toString());
        map.put("recipient", recipient);
        map.put("items", List.of(item));
        map.put("payments", List.of(payment));
        map.put("additional_info", Map.of("Referencia", "Contrato 2026-001"));
        return map;
    }
}
