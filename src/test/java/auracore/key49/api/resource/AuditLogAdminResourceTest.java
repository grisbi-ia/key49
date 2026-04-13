package auracore.key49.api.resource;

import java.sql.SQLException;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import auracore.key49.core.service.AuditService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;

/**
 * Test de integración para el endpoint GET /v1/admin/audit-log. Inserta
 * entradas de audit_log vía AuditService y verifica filtros y paginación.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditLogAdminResourceTest {

    private static final String ADMIN_TOKEN = "test-admin-token";
    private static final String ADMIN_ENDPOINT = "/v1/admin/audit-log";

    @Inject
    AuditService auditService;

    @Inject
    javax.sql.DataSource dataSource;

    private UUID tenantIdA;
    private UUID tenantIdB;

    @BeforeAll
    void setupTenantAndAuditData() throws SQLException {
        tenantIdA = UUID.randomUUID();
        tenantIdB = UUID.randomUUID();

        try (var conn = dataSource.getConnection()) {
            var insertTenant = """
                    INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 10000, 10000, 10000, 'active', now(), now())""";

            try (var ps = conn.prepareStatement(insertTenant)) {
                ps.setObject(1, tenantIdA.toString());
                ps.setString(2, "1790000000001");
                ps.setString(3, "Audit Tenant A");
                ps.setString(4, "Audit A");
                ps.setString(5, "Quito");
                ps.setString(6, "tenant_audit_a");
                ps.executeUpdate();
            }

            try (var ps = conn.prepareStatement(insertTenant)) {
                ps.setObject(1, tenantIdB.toString());
                ps.setString(2, "1790000000002");
                ps.setString(3, "Audit Tenant B");
                ps.setString(4, "Audit B");
                ps.setString(5, "Guayaquil");
                ps.setString(6, "tenant_audit_b");
                ps.executeUpdate();
            }
        }

        // Insert audit entries via AuditService
        auditService.record(tenantIdA, "fec_test", "document.voided", "document",
                UUID.randomUUID(), "10.0.0.1", "{\"reason\":\"error\"}");
        auditService.record(tenantIdA, "fec_test", "api_key.created", "api_key",
                UUID.randomUUID(), "10.0.0.2", "{\"name\":\"ERP Key\"}");
        auditService.record(tenantIdB, "admin", "tenant.created", "tenant",
                tenantIdB, "192.168.1.1", "{\"ruc\":\"1790000000002\"}");
        auditService.record(tenantIdA, "portal", "portal.login", "session",
                null, "203.0.113.50", null);
    }

    @Test
    @Order(1)
    void requiresAdminToken() {
        RestAssured.given()
                .when().get(ADMIN_ENDPOINT)
                .then()
                .statusCode(403);
    }

    @Test
    @Order(2)
    void returnsAllEntries() {
        RestAssured.given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .when().get(ADMIN_ENDPOINT)
                .then()
                .statusCode(200)
                .body("data", hasSize(greaterThanOrEqualTo(4)))
                .body("meta.page", equalTo(1))
                .body("meta.per_page", equalTo(50));
    }

    @Test
    @Order(3)
    void filtersByTenantId() {
        RestAssured.given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .queryParam("tenant_id", tenantIdB.toString())
                .when().get(ADMIN_ENDPOINT)
                .then()
                .statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].action", equalTo("tenant.created"))
                .body("data[0].actor", equalTo("admin"));
    }

    @Test
    @Order(4)
    void filtersByAction() {
        RestAssured.given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .queryParam("action", "document.voided")
                .when().get(ADMIN_ENDPOINT)
                .then()
                .statusCode(200)
                .body("data", hasSize(greaterThanOrEqualTo(1)))
                .body("data[0].action", equalTo("document.voided"));
    }

    @Test
    @Order(5)
    void filtersByDateRange() {
        // Use today's date (Ecuador timezone) to capture records inserted in @BeforeAll
        var today = java.time.LocalDate.now(auracore.key49.core.Key49Constants.EC_ZONE).toString();
        RestAssured.given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .queryParam("date_from", today)
                .queryParam("date_to", today)
                .when().get(ADMIN_ENDPOINT)
                .then()
                .statusCode(200)
                .body("data", hasSize(greaterThanOrEqualTo(4)));
    }

    @Test
    @Order(6)
    void paginatesResults() {
        RestAssured.given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .queryParam("per_page", 2)
                .queryParam("page", 1)
                .when().get(ADMIN_ENDPOINT)
                .then()
                .statusCode(200)
                .body("data", hasSize(2))
                .body("meta.per_page", equalTo(2))
                .body("meta.total", greaterThanOrEqualTo(4))
                .body("meta.total_pages", greaterThanOrEqualTo(2));
    }

    @Test
    @Order(7)
    void returnsCorrectResponseShape() {
        RestAssured.given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .queryParam("tenant_id", tenantIdB.toString())
                .when().get(ADMIN_ENDPOINT)
                .then()
                .statusCode(200)
                .body("data[0].id", notNullValue())
                .body("data[0].tenant_id", equalTo(tenantIdB.toString()))
                .body("data[0].actor", notNullValue())
                .body("data[0].action", notNullValue())
                .body("data[0].resource", notNullValue())
                .body("data[0].created_at", notNullValue());
    }

    @Test
    @Order(8)
    void returnsEmptyForFutureDateRange() {
        RestAssured.given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .queryParam("date_from", "2099-01-01")
                .queryParam("date_to", "2099-12-31")
                .when().get(ADMIN_ENDPOINT)
                .then()
                .statusCode(200)
                .body("data", hasSize(0))
                .body("meta.total", equalTo(0));
    }

    @Test
    @Order(9)
    void combinedFilters() {
        RestAssured.given()
                .header("X-Admin-Token", ADMIN_TOKEN)
                .queryParam("tenant_id", tenantIdA.toString())
                .queryParam("action", "api_key.created")
                .when().get(ADMIN_ENDPOINT)
                .then()
                .statusCode(200)
                .body("data", hasSize(1))
                .body("data[0].actor", equalTo("fec_test"))
                .body("data[0].resource", equalTo("api_key"));
    }
}
