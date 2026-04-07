package auracore.key49.api.resource;

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
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
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
    PgPool pgPool;

    private String rawApiKey;
    private UUID tenantId;
    private String createdApiKeyId;

    @BeforeAll
    void setupTenantAndApiKey() {
        tenantId = UUID.randomUUID();
        var generated = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        rawApiKey = generated.rawKey();

        pgPool.preparedQuery("""
                        INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                            required_accounting, micro_enterprise_regime, environment,
                            emission_type, rate_limit_rpm, status, created_at, updated_at)
                        VALUES ($1, $2, $3, $4, $5, $6, false, false, 'test', 1, 10000, 'active', now(), now())""")
                .execute(Tuple.of(tenantId, TENANT_RUC, "ApiKey Test S.A.", "ApiKey Test", "Quito", TENANT_SCHEMA))
                .await().indefinitely();

        pgPool.preparedQuery("""
                        INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status, created_at)
                        VALUES ($1, $2, $3, $4, $5, '*', 'active', now())""")
                .execute(Tuple.of(UUID.randomUUID(), tenantId, generated.keyPrefix(), generated.hash(), "initial-key"))
                .await().indefinitely();

        pgPool.query("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA).execute()
                .await().indefinitely();
    }

    // ── POST /tenant/api-keys — crear ──

    @Test
    @Order(1)
    void create_returnsNewKeyWithRawKey() {
        var body = """
                {
                  "name": "ERP Integration Key",
                  "environment": "test"
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
                .body("data.key_prefix", equalTo("fec_test"))
                .body("data.raw_key", notNullValue())
                .body("data.raw_key", startsWith("fec_test_"))
                .body("data.status", equalTo("active"))
                .body("data.permissions", equalTo("*"))
                .body("meta.request_id", notNullValue())
                .extract().path("data.id");
    }

    @Test
    @Order(2)
    void create_withPermissionsAndExpiry() {
        var body = """
                {
                  "name": "Limited Key",
                  "environment": "test",
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
                .body("data.raw_key", startsWith("fec_test_"));
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
    @Order(4)
    void create_invalidEnvironment_returns400() {
        var body = """
                {
                  "name": "Bad Key",
                  "environment": "staging"
                }
                """;

        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .contentType(ContentType.JSON)
                .body(body)
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
