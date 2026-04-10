package auracore.key49.api.resource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import auracore.key49.core.Key49Constants;
import auracore.key49.core.service.ApiKeyService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

import java.sql.SQLException;
import java.sql.Timestamp;

/**
 * Test de integracion para el endpoint de metricas (/v1/metrics/summary).
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MetricsEndToEndTest {

    private static final String TENANT_SCHEMA = "tenant_metrics_e2e";
    private static final String TENANT_RUC = "1792146739001";

    @Inject
    javax.sql.DataSource dataSource;

    private String rawApiKey;
    private UUID tenantId;

    @BeforeAll
    void setupTenantWithDocuments() throws SQLException {
        tenantId = UUID.randomUUID();
        var generated = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        rawApiKey = generated.rawKey();

        var certExpiration = Instant.now().plus(90, ChronoUnit.DAYS);
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        var yesterday = today.minusDays(1);
        var authDate = Instant.now().minus(1, ChronoUnit.HOURS);

        try (var conn = dataSource.getConnection()) {
            // 1. Insert tenant with certificate expiration
            try (var ps = conn.prepareStatement("""
                    INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, certificate_expiration, created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 10000, 10000, 10000, 'active', ?, now(), now())""")) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, TENANT_RUC);
                ps.setString(3, "Metrics Test S.A.");
                ps.setString(4, "Metrics");
                ps.setString(5, "Quito");
                ps.setString(6, TENANT_SCHEMA);
                ps.setTimestamp(7, Timestamp.from(certExpiration));
                ps.executeUpdate();
            }

            // 2. Insert API key
            try (var ps = conn.prepareStatement("""
                    INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status, created_at)
                    VALUES (?::uuid, ?::uuid, ?, ?, ?, '*', 'active', now())""")) {
                ps.setObject(1, UUID.randomUUID().toString());
                ps.setObject(2, tenantId.toString());
                ps.setString(3, generated.keyPrefix());
                ps.setString(4, generated.hash());
                ps.setString(5, "metrics-key");
                ps.executeUpdate();
            }

            // 3. Create tenant schema with documents table
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
                        CONSTRAINT uq_metrics_doc_number UNIQUE (document_type, establishment, issue_point, sequence_number),
                        CONSTRAINT uq_metrics_doc_access_key UNIQUE (access_key),
                        CONSTRAINT uq_metrics_doc_idempotency UNIQUE (idempotency_key)
                    )""".formatted(TENANT_SCHEMA));
            }
        }

        // 4. Insert documents with varied statuses for today
        insertDocument(today, "AUTHORIZED", "001", authDate);
        insertDocument(today, "AUTHORIZED", "002", authDate);
        insertDocument(today, "AUTHORIZED", "003", authDate);
        insertDocument(today, "NOTIFIED", "004", authDate);
        insertDocument(today, "REJECTED", "005", null);
        insertDocument(today, "CREATED", "006", null);
        insertDocument(today, "FAILED", "007", null);

        // 5. Insert documents for yesterday
        insertDocument(yesterday, "AUTHORIZED", "008", authDate.minus(1, ChronoUnit.DAYS));
        insertDocument(yesterday, "AUTHORIZED", "009", authDate.minus(1, ChronoUnit.DAYS));
        insertDocument(yesterday, "VOIDED", "010", authDate.minus(1, ChronoUnit.DAYS));
    }

    private void insertDocument(LocalDate issueDate, String status, String seq, Instant authDate) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                         sequence_number, recipient_id_type, recipient_id, recipient_name,
                         issue_date, status, authorization_date, request_payload, created_at, updated_at)
                     VALUES (?::uuid, '01', '001', '001', ?, '05', '1712345678001', 'Cliente Test',
                         ?, ?, ?, '{}', now(), now())""".formatted(TENANT_SCHEMA))) {
            ps.setObject(1, UUID.randomUUID().toString());
            ps.setString(2, seq.repeat(3));
            ps.setObject(3, issueDate);
            ps.setString(4, status);
            ps.setTimestamp(5, authDate != null ? Timestamp.from(authDate) : null);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Order(1)
    void summary_returnsTodayCounts() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().get("/v1/metrics/summary")
                .then()
                .statusCode(200)
                .body("data.today.total", equalTo(7))
                .body("data.today.authorized", equalTo(4))
                .body("data.today.rejected", equalTo(1))
                .body("data.today.pending", equalTo(1))
                .body("data.today.failed", equalTo(1));
    }

    @Test
    @Order(2)
    void summary_returnsMonthCounts() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().get("/v1/metrics/summary")
                .then()
                .statusCode(200)
                .body("data.month.total", equalTo(10))
                .body("data.month.authorized", equalTo(7))
                .body("data.month.rejected", equalTo(1))
                .body("data.month.pending", equalTo(1))
                .body("data.month.failed", equalTo(1));
    }

    @Test
    @Order(3)
    void summary_returnsCertificateDays() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().get("/v1/metrics/summary")
                .then()
                .statusCode(200)
                .body("data.certificate_expires_in_days", greaterThanOrEqualTo(89));
    }

    @Test
    @Order(4)
    void summary_returnsLastInvoiceAt() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().get("/v1/metrics/summary")
                .then()
                .statusCode(200)
                .body("data.last_invoice_at", notNullValue());
    }

    @Test
    @Order(5)
    void summary_hasApiResponseWrapper() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().get("/v1/metrics/summary")
                .then()
                .statusCode(200)
                .body("data", notNullValue())
                .body("meta.request_id", notNullValue());
    }

    @Test
    @Order(6)
    void summary_withoutAuth_returns401() {
        RestAssured.given()
                .accept(ContentType.JSON)
                .when().get("/v1/metrics/summary")
                .then()
                .statusCode(401);
    }

    @Test
    @Order(7)
    void summary_withNoCertificate_returnsNegativeDays() throws SQLException {
        var tenantId2 = UUID.randomUUID();
        var generated2 = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        var schema2 = "tenant_metrics_nocert";

        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("""
                    INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 10000, 10000, 10000, 'active', now(), now())""")) {
                ps.setObject(1, tenantId2.toString());
                ps.setString(2, "1790016919001");
                ps.setString(3, "NoCert S.A.");
                ps.setString(4, "NoCert");
                ps.setString(5, "Guayaquil");
                ps.setString(6, schema2);
                ps.executeUpdate();
            }

            try (var ps = conn.prepareStatement("""
                    INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status, created_at)
                    VALUES (?::uuid, ?::uuid, ?, ?, ?, '*', 'active', now())""")) {
                ps.setObject(1, UUID.randomUUID().toString());
                ps.setObject(2, tenantId2.toString());
                ps.setString(3, generated2.keyPrefix());
                ps.setString(4, generated2.hash());
                ps.setString(5, "nocert-key");
                ps.executeUpdate();
            }

            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + schema2);
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
                        CONSTRAINT uq_nocert_doc_number UNIQUE (document_type, establishment, issue_point, sequence_number)
                    )""".formatted(schema2));
            }
        }

        RestAssured.given()
                .header("Authorization", "Bearer " + generated2.rawKey())
                .accept(ContentType.JSON)
                .when().get("/v1/metrics/summary")
                .then()
                .statusCode(200)
                .body("data.certificate_expires_in_days", equalTo(-1))
                .body("data.today.total", equalTo(0))
                .body("data.month.total", equalTo(0));
    }
}
