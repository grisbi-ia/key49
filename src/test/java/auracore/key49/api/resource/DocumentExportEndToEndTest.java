package auracore.key49.api.resource;

import auracore.key49.core.Key49Constants;
import auracore.key49.core.service.ApiKeyService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test end-to-end del endpoint de exportación CSV.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentExportEndToEndTest {

    private static final String TENANT_SCHEMA = "tenant_export_e2e";

    @Inject
    javax.sql.DataSource dataSource;

    private String rawApiKey;
    private UUID tenantId;

    @BeforeAll
    void setup() throws Exception {
        tenantId = UUID.randomUUID();
        var generated = ApiKeyService.generate();
        rawApiKey = generated.rawKey();

        try (var conn = dataSource.getConnection()) {
            // Create tenant
            try (var ps = conn.prepareStatement("""
                    INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 10000, 10000, 10000, 'active', now(), now())""")) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, "0992000000001");
                ps.setString(3, "Export Test Corp S.A.");
                ps.setString(4, "Export Test");
                ps.setString(5, "Guayaquil");
                ps.setString(6, TENANT_SCHEMA);
                ps.executeUpdate();
            }

            // Create API key
            try (var ps = conn.prepareStatement("""
                    INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status, created_at)
                    VALUES (?::uuid, ?::uuid, ?, ?, ?, '*', 'active', now())""")) {
                ps.setObject(1, UUID.randomUUID().toString());
                ps.setObject(2, tenantId.toString());
                ps.setString(3, generated.keyPrefix());
                ps.setString(4, generated.hash());
                ps.setString(5, "export-e2e-key");
                ps.executeUpdate();
            }

            // Create schema + documents table
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
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""".formatted(TENANT_SCHEMA));
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
                    )""".formatted(TENANT_SCHEMA));
            }

            // Insert test documents
            var today = LocalDate.now(Key49Constants.EC_ZONE);
            for (int i = 1; i <= 5; i++) {
                try (var ps = conn.prepareStatement("""
                        INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                            sequence_number, access_key, recipient_id_type, recipient_id, recipient_name,
                            subtotal_before_tax, vat_amount, total_amount,
                            issue_date, status, authorization_date, created_at, updated_at)
                        VALUES (?::uuid, '01', '001', '001', ?,
                            ?, '04', '0990000000001', 'ACME Corp S.A.',
                            100.00, 15.00, 115.00, ?, ?, ?::timestamptz, now(), now())""".formatted(TENANT_SCHEMA))) {
                    ps.setObject(1, UUID.randomUUID().toString());
                    ps.setString(2, String.format("%09d", i));
                    ps.setString(3, "060420250109900000000010010010000000%03d1234567811".formatted(i));
                    ps.setObject(4, today.minusDays(i - 1));
                    ps.setString(5, i <= 3 ? "AUTHORIZED" : "CREATED");
                    ps.setString(6, i <= 3 ? java.time.Instant.now().toString() : null);
                    ps.executeUpdate();
                }
            }

            // Insert a document with comma in recipient name
            try (var ps = conn.prepareStatement("""
                    INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                        sequence_number, access_key, recipient_id_type, recipient_id, recipient_name,
                        subtotal_before_tax, vat_amount, total_amount,
                        issue_date, status, created_at, updated_at)
                    VALUES (?::uuid, '01', '001', '001', '000000006',
                        '0604202501099000000000100100100000000061234567811', '04', '1790016919001',
                        'Company, Inc.', 200.00, 30.00, 230.00,
                        ?, 'AUTHORIZED', now(), now())""".formatted(TENANT_SCHEMA))) {
                ps.setObject(1, UUID.randomUUID().toString());
                ps.setObject(2, today);
                ps.executeUpdate();
            }
        }
    }

    private String authHeader() {
        return "Bearer " + rawApiKey;
    }

    private LocalDate today() {
        return LocalDate.now(Key49Constants.EC_ZONE);
    }

    @Test
    @Order(1)
    void shouldExportCsv() {
        var body = RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("format", "csv")
                .queryParam("from", today().minusDays(10).toString())
                .queryParam("to", today().toString())
                .when()
                .get("/v1/documents/export")
                .then()
                .statusCode(200)
                .contentType(containsString("text/csv"))
                .header("Content-Disposition", containsString("attachment"))
                .header("Content-Disposition", containsString(".csv"))
                .header("X-Request-Id", startsWith("req_"))
                .header("X-Export-Count", notNullValue())
                .extract().body().asString();

        var lines = body.split("\n");
        assertTrue(lines.length >= 7, "Should have header + at least 6 data rows, got " + lines.length);
        assertEquals("access_key,document_type,establishment,issue_point,sequence_number,"
                + "recipient_id,recipient_name,total_amount,status,issue_date,authorization_date",
                lines[0].trim());
    }

    @Test
    @Order(1)
    void shouldFilterByStatus() {
        var body = RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("from", today().minusDays(10).toString())
                .queryParam("to", today().toString())
                .queryParam("status", "AUTHORIZED")
                .when()
                .get("/v1/documents/export")
                .then()
                .statusCode(200)
                .header("X-Export-Count", notNullValue())
                .extract().body().asString();

        var lines = body.split("\n");
        // All data rows should have AUTHORIZED status
        for (int i = 1; i < lines.length; i++) {
            assertTrue(lines[i].contains("AUTHORIZED"),
                    "Row " + i + " should be AUTHORIZED: " + lines[i]);
        }
    }

    @Test
    @Order(1)
    void shouldEscapeCsvSpecialChars() {
        var body = RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("from", today().toString())
                .queryParam("to", today().toString())
                .when()
                .get("/v1/documents/export")
                .then()
                .statusCode(200)
                .extract().body().asString();

        // The document with "Company, Inc." should be quoted
        assertTrue(body.contains("\"Company, Inc.\""), "Should escape comma in recipient name");
    }

    @Test
    @Order(1)
    void shouldSetFilenameWithToDate() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("from", today().minusDays(5).toString())
                .queryParam("to", today().toString())
                .when()
                .get("/v1/documents/export")
                .then()
                .statusCode(200)
                .header("Content-Disposition",
                        containsString("key49-export-" + today() + ".csv"));
    }

    @Test
    @Order(1)
    void shouldRejectMissingDateParams() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/documents/export")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.message", containsString("from"));
    }

    @Test
    @Order(1)
    void shouldRejectInvalidDateRange() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("from", today().toString())
                .queryParam("to", today().minusDays(5).toString())
                .when()
                .get("/v1/documents/export")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    @Order(1)
    void shouldRejectUnsupportedFormat() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("format", "xml")
                .queryParam("from", today().toString())
                .queryParam("to", today().toString())
                .when()
                .get("/v1/documents/export")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    @Order(1)
    void shouldRejectInvalidStatus() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("from", today().toString())
                .queryParam("to", today().toString())
                .queryParam("status", "INVALID")
                .when()
                .get("/v1/documents/export")
                .then()
                .statusCode(anyOf(equalTo(400), equalTo(500)));
    }

    @Test
    @Order(1)
    void shouldReturnEmptyCsvForNoResults() {
        var body = RestAssured.given()
                .header("Authorization", authHeader())
                .queryParam("from", "2020-01-01")
                .queryParam("to", "2020-01-31")
                .when()
                .get("/v1/documents/export")
                .then()
                .statusCode(200)
                .header("X-Export-Count", equalTo("0"))
                .extract().body().asString();

        var lines = body.strip().split("\n");
        assertEquals(1, lines.length, "Should have only header row");
    }

    @Test
    @Order(1)
    void shouldRequireAuthentication() {
        RestAssured.given()
                .queryParam("from", today().toString())
                .queryParam("to", today().toString())
                .when()
                .get("/v1/documents/export")
                .then()
                .statusCode(401);
    }
}
