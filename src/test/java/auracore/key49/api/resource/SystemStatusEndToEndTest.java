package auracore.key49.api.resource;

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

import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;

/**
 * Test end-to-end del endpoint GET /v1/system/status.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SystemStatusEndToEndTest {

    private static final String TENANT_SCHEMA = "tenant_sysstatus_e2e";

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
            try (var ps = conn.prepareStatement("""
                    INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 10000, 10000, 10000, 'active', now(), now())""")) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, "0993000000001");
                ps.setString(3, "SysStatus Test Corp");
                ps.setString(4, "SysStatus Test");
                ps.setString(5, "Guayaquil");
                ps.setString(6, TENANT_SCHEMA);
                ps.executeUpdate();
            }

            try (var ps = conn.prepareStatement("""
                    INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status, created_at)
                    VALUES (?::uuid, ?::uuid, ?, ?, ?, '*', 'active', now())""")) {
                ps.setObject(1, UUID.randomUUID().toString());
                ps.setObject(2, tenantId.toString());
                ps.setString(3, generated.keyPrefix());
                ps.setString(4, generated.hash());
                ps.setString(5, "sysstatus-e2e-key");
                ps.executeUpdate();
            }

            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA);
            }
        }
    }

    @Test
    @Order(1)
    void shouldReturnSystemStatus() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .when()
                .get("/v1/system/status")
                .then()
                .statusCode(200)
                .header("X-Request-Id", notNullValue())
                .body("data.overall", is(oneOf("operational", "outage")))
                .body("data.components", hasKey("sri_reception"))
                .body("data.components", hasKey("sri_authorization"))
                .body("data.components", hasKey("storage"))
                .body("data.components", hasKey("database"))
                .body("data.components", hasKey("queues"))
                .body("meta.request_id", notNullValue())
                .body("meta.timestamp", notNullValue());
    }

    @Test
    @Order(2)
    void shouldIncludeComponentDetails() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .when()
                .get("/v1/system/status")
                .then()
                .statusCode(200)
                .body("data.components.database.status", is("operational"))
                .body("data.components.database.name", is("Datasource pool"));
    }

    @Test
    @Order(3)
    void shouldShowStorageStatus() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .when()
                .get("/v1/system/status")
                .then()
                .statusCode(200)
                .body("data.components.storage.name", is("MinIO bucket"))
                .body("data.components.storage.status", is(oneOf("operational", "down")));
    }

    @Test
    @Order(4)
    void shouldShowQueueStatus() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .when()
                .get("/v1/system/status")
                .then()
                .statusCode(200)
                .body("data.components.queues.name", is("RabbitMQ queue depth"))
                .body("data.components.queues.status", is(oneOf("operational", "down")));
    }

    @Test
    @Order(5)
    void shouldReturnSriReceptionStatus() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .when()
                .get("/v1/system/status")
                .then()
                .statusCode(200)
                .body("data.components.sri_reception.name", is("SRI Recepción"))
                .body("data.components.sri_reception.status", is(oneOf("operational", "down")));
    }

    @Test
    @Order(6)
    void shouldReturnSriAuthorizationStatus() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .when()
                .get("/v1/system/status")
                .then()
                .statusCode(200)
                .body("data.components.sri_authorization.name", is("SRI Autorización"))
                .body("data.components.sri_authorization.status", is(oneOf("operational", "down")));
    }

    @Test
    @Order(7)
    void shouldRequireAuthentication() {
        RestAssured.given()
                .when()
                .get("/v1/system/status")
                .then()
                .statusCode(401);
    }

    @Test
    @Order(8)
    void shouldReturnJsonContentType() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .when()
                .get("/v1/system/status")
                .then()
                .statusCode(200)
                .contentType("application/json");
    }
}
