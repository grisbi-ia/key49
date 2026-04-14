package auracore.key49.api.resource;

import java.sql.SQLException;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import auracore.key49.core.service.ApiKeyService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * Test de integración para endpoints de gestión de API keys
 * (/v1/tenant/api-keys).
 *
 * <p>
 * Crea un tenant con API key inicial (para autenticación) y ejercita creación,
 * listado, consulta y revocación de API keys adicionales.</p>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiKeyEndToEndTest {

    private static final String TENANT_SCHEMA = "tenant_apikey_e2e";
    private static final String TENANT_RUC = "1712345678001";

    @Inject
    javax.sql.DataSource dataSource;

    private String rawApiKey;
    private UUID tenantId;
    private String createdApiKeyId;

    @BeforeAll
    void setupTenantAndApiKey() throws SQLException {
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
                ps.setString(2, TENANT_RUC);
                ps.setString(3, "ApiKey Test S.A.");
                ps.setString(4, "ApiKey Test");
                ps.setString(5, "Quito");
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
                ps.setString(5, "initial-key");
                ps.executeUpdate();
            }

            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA);
            }
        }
    }

    // ── POST /tenant/api-keys — crear ──
    @Test
    @Order(1)
    void create_returnsNewKeyWithRawKey() {
        var body = """
                {
                  "name": "ERP Integration Key"
                }
                """;

        createdApiKeyId = RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/v1/tenant/api-keys")
                .then()
                .statusCode(201)
                .body("data.id", notNullValue())
                .body("data.name", equalTo("ERP Integration Key"))
                .body("data.key_prefix", equalTo("k49"))
                .body("data.raw_key", notNullValue())
                .body("data.raw_key", startsWith("k49_"))
                .body("data.status", equalTo("active"))
                .body("data.permissions", equalTo("*"))
                .body("meta.request_id", notNullValue())
                .extract().<String>path("data.id");
    }

    @Test
    @Order(2)
    void create_withPermissionsAndExpiry() {
        var body = """
                {
                  "name": "Limited Key",
                  "permissions": "invoices:read",
                  "expires_at": "2027-12-31T23:59:59Z"
                }
                """;

        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .contentType(ContentType.JSON)
                .body(body)
                .when().post("/v1/tenant/api-keys")
                .then()
                .statusCode(201)
                .body("data.permissions", equalTo("invoices:read"))
                .body("data.expires_at", notNullValue())
                .body("data.raw_key", startsWith("k49_"));
    }

    @Test
    @Order(3)
    void create_withoutName_returns400() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .contentType(ContentType.JSON)
                .body("{}")
                .when().post("/v1/tenant/api-keys")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }

    @Test
    @Order(5)
    void create_withoutAuth_returns401() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"name\": \"Hack Key\"}")
                .when().post("/v1/tenant/api-keys")
                .then()
                .statusCode(401);
    }

    // ── GET /tenant/api-keys — listar ──
    @Test
    @Order(6)
    void list_returnsAllKeysForTenant() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().get("/v1/tenant/api-keys")
                .then()
                .statusCode(200)
                .body("data", hasSize(greaterThanOrEqualTo(3))) // initial + 2 created
                .body("data[0].key_prefix", notNullValue())
                .body("data[0].raw_key", nullValue()); // raw_key never in list
    }

    @Test
    @Order(7)
    void list_withoutAuth_returns401() {
        RestAssured.given()
                .accept(ContentType.JSON)
                .when().get("/v1/tenant/api-keys")
                .then()
                .statusCode(401);
    }

    // ── GET /tenant/api-keys/:id — consultar ──
    @Test
    @Order(8)
    void getById_returnsKey() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().get("/v1/tenant/api-keys/" + createdApiKeyId)
                .then()
                .statusCode(200)
                .body("data.id", equalTo(createdApiKeyId))
                .body("data.name", equalTo("ERP Integration Key"))
                .body("data.status", equalTo("active"))
                .body("data.raw_key", nullValue()); // raw_key only on create
    }

    @Test
    @Order(9)
    void getById_notFound_returns404() {
        var fakeId = UUID.randomUUID().toString();
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().get("/v1/tenant/api-keys/" + fakeId)
                .then()
                .statusCode(404)
                .body("error.code", equalTo("API_KEY_NOT_FOUND"));
    }

    // ── DELETE /tenant/api-keys/:id — revocar ──
    @Test
    @Order(10)
    void revoke_changesStatusToRevoked() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().delete("/v1/tenant/api-keys/" + createdApiKeyId)
                .then()
                .statusCode(200)
                .body("data.id", equalTo(createdApiKeyId))
                .body("data.status", equalTo("revoked"));
    }

    @Test
    @Order(11)
    void revoke_alreadyRevoked_returns409() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().delete("/v1/tenant/api-keys/" + createdApiKeyId)
                .then()
                .statusCode(409)
                .body("error.code", equalTo("ALREADY_REVOKED"));
    }

    @Test
    @Order(12)
    void getById_afterRevoke_showsRevoked() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().get("/v1/tenant/api-keys/" + createdApiKeyId)
                .then()
                .statusCode(200)
                .body("data.status", equalTo("revoked"));
    }

    @Test
    @Order(13)
    void revoke_notFound_returns404() {
        var fakeId = UUID.randomUUID().toString();
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().delete("/v1/tenant/api-keys/" + fakeId)
                .then()
                .statusCode(404)
                .body("error.code", equalTo("API_KEY_NOT_FOUND"));
    }

    @Test
    @Order(14)
    void revoke_withoutAuth_returns401() {
        RestAssured.given()
                .accept(ContentType.JSON)
                .when().delete("/v1/tenant/api-keys/" + createdApiKeyId)
                .then()
                .statusCode(401);
    }
}
