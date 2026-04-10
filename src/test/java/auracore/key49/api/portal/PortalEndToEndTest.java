package auracore.key49.api.portal;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import auracore.key49.core.service.ApiKeyService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;

/**
 * Tests end-to-end del portal web de consulta.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PortalEndToEndTest {

    private static final String TENANT_SCHEMA = "tenant_portal_e2e";

    @Inject
    javax.sql.DataSource dataSource;

    private String rawApiKey;
    private UUID tenantId;
    private UUID documentId;
    private String sessionCookie;

    @BeforeAll
    void setup() throws SQLException {
        tenantId = UUID.randomUUID();
        var generated = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        rawApiKey = generated.rawKey();

        try (var conn = dataSource.getConnection()) {
            // Create tenant
            try (var ps = conn.prepareStatement("""
                    INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, status, created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 100, 'active', now(), now())""")) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, "0990000000001");
                ps.setString(3, "Portal Test Corp S.A.");
                ps.setString(4, "Portal Test");
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
                ps.setString(5, "portal-e2e-key");
                ps.executeUpdate();
            }

            // Create schema + tables
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
            documentId = UUID.randomUUID();
            try (var ps = conn.prepareStatement("""
                    INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                        sequence_number, access_key, recipient_id_type, recipient_id, recipient_name,
                        recipient_email, subtotal_before_tax, vat_amount, total_amount,
                        issue_date, status, created_at, updated_at)
                    VALUES (?::uuid, '01', '001', '001', '000000001',
                        '0604202501099000000000100100100000000011234567811', '04', '0990000000001',
                        'ACME Corp S.A.', 'test@acme.com', 100.00, 15.00, 115.00,
                        ?, 'AUTHORIZED', now(), now())""".formatted(TENANT_SCHEMA))) {
                ps.setObject(1, documentId.toString());
                ps.setObject(2, LocalDate.now());
                ps.executeUpdate();
            }

            // Insert second document
            try (var ps = conn.prepareStatement("""
                    INSERT INTO %s.documents (document_id, document_type, establishment, issue_point,
                        sequence_number, access_key, recipient_id_type, recipient_id, recipient_name,
                        subtotal_before_tax, vat_amount, total_amount,
                        issue_date, status, created_at, updated_at)
                    VALUES (?::uuid, '01', '001', '001', '000000002',
                        '0604202501099000000000100100100000000021234567812', '04', '1790016919001',
                        'Beta Inc.', 200.00, 30.00, 230.00,
                        ?, 'CREATED', now(), now())""".formatted(TENANT_SCHEMA))) {
                ps.setObject(1, UUID.randomUUID().toString());
                ps.setObject(2, LocalDate.now());
                ps.executeUpdate();
            }
        }
    }

    @Test
    @Order(1)
    void shouldRedirectToLogin_whenNoSession() {
        RestAssured.given()
                .redirects().follow(false)
                .when()
                .get("/portal/")
                .then()
                .statusCode(303)
                .header("Location", containsString("/portal/login"));
    }

    @Test
    @Order(2)
    void shouldRenderLoginPage() {
        RestAssured.given()
                .when()
                .get("/portal/login")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("Key49"))
                .body(containsString("api_key"));
    }

    @Test
    @Order(3)
    void shouldRejectInvalidApiKey() {
        RestAssured.given()
                .redirects().follow(false)
                .formParam("api_key", "fec_test_invalid_key_123456")
                .when()
                .post("/portal/login")
                .then()
                .statusCode(303)
                .header("Location", containsString("/portal/login?error=invalid"));
    }

    @Test
    @Order(4)
    void shouldLoginWithValidApiKey() {
        var response = RestAssured.given()
                .redirects().follow(false)
                .formParam("api_key", rawApiKey)
                .when()
                .post("/portal/login")
                .then()
                .statusCode(303)
                .header("Location", containsString("/portal/"))
                .cookie("KEY49_SESSION", notNullValue())
                .extract();

        sessionCookie = response.cookie("KEY49_SESSION");
    }

    @Test
    @Order(5)
    void shouldRenderDashboard() {
        RestAssured.given()
                .cookie("KEY49_SESSION", sessionCookie)
                .when()
                .get("/portal/")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("Documentos"))
                .body(containsString("ACME Corp"))
                .body(containsString("000000001"))
                .body(containsString("Portal Test Corp"));
    }

    @Test
    @Order(6)
    void shouldFilterByStatus() {
        RestAssured.given()
                .cookie("KEY49_SESSION", sessionCookie)
                .queryParam("status", "AUTHORIZED")
                .when()
                .get("/portal/")
                .then()
                .statusCode(200)
                .body(containsString("ACME Corp"))
                .body(not(containsString("Beta Inc.")));
    }

    @Test
    @Order(6)
    void shouldFilterByDocumentType() {
        RestAssured.given()
                .cookie("KEY49_SESSION", sessionCookie)
                .queryParam("doc_type", "01")
                .when()
                .get("/portal/")
                .then()
                .statusCode(200)
                .body(containsString("Factura"));
    }

    @Test
    @Order(7)
    void shouldRenderDocumentDetail() {
        RestAssured.given()
                .cookie("KEY49_SESSION", sessionCookie)
                .when()
                .get("/portal/documents/" + documentId)
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("001-001-000000001"))
                .body(containsString("ACME Corp"))
                .body(containsString("test@acme.com"))
                .body(containsString("115.00"))
                .body(containsString("Autorizado"));
    }

    @Test
    @Order(8)
    void shouldShowErrorForNonExistentDocument() {
        RestAssured.given()
                .cookie("KEY49_SESSION", sessionCookie)
                .when()
                .get("/portal/documents/" + UUID.randomUUID())
                .then()
                .statusCode(200)
                .body(containsString("no encontrado"));
    }

    @Test
    @Order(9)
    void shouldReturnStatusBadge() {
        RestAssured.given()
                .cookie("KEY49_SESSION", sessionCookie)
                .when()
                .get("/portal/documents/" + documentId + "/status")
                .then()
                .statusCode(200)
                .body(containsString("Autorizado"))
                .body(containsString("status-ok"));
    }

    @Test
    @Order(9)
    void shouldReturn404ForXmlWhenPathIsNull() {
        RestAssured.given()
                .cookie("KEY49_SESSION", sessionCookie)
                .when()
                .get("/portal/documents/" + documentId + "/xml")
                .then()
                .statusCode(404)
                .body(containsString("no disponible"));
    }

    @Test
    @Order(9)
    void shouldReturn404ForRideWhenPathIsNull() {
        RestAssured.given()
                .cookie("KEY49_SESSION", sessionCookie)
                .when()
                .get("/portal/documents/" + documentId + "/ride")
                .then()
                .statusCode(404)
                .body(containsString("no disponible"));
    }

    @Test
    @Order(9)
    void shouldRedirectToLoginForXmlWithoutSession() {
        RestAssured.given()
                .redirects().follow(false)
                .when()
                .get("/portal/documents/" + documentId + "/xml")
                .then()
                .statusCode(303)
                .header("Location", containsString("/portal/login"));
    }

    @Test
    @Order(10)
    void shouldLogout() {
        RestAssured.given()
                .cookie("KEY49_SESSION", sessionCookie)
                .redirects().follow(false)
                .when()
                .get("/portal/logout")
                .then()
                .statusCode(303)
                .header("Location", containsString("/portal/login"));

        RestAssured.given()
                .cookie("KEY49_SESSION", sessionCookie)
                .redirects().follow(false)
                .when()
                .get("/portal/")
                .then()
                .statusCode(303)
                .header("Location", containsString("/portal/login"));
    }
}
