package auracore.key49.api.resource;

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

import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Test de integración para endpoints de perfil de tenant (/v1/tenant).
 *
 * <p>
 * Crea un tenant con API key y ejercita GET/PUT /tenant/profile y GET
 * /tenant/certificate/status.</p>
 */

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantProfileEndToEndTest {

    private static final String TENANT_SCHEMA = "tenant_profile_e2e";
    private static final String TENANT_RUC = "0992877878001";

    @Inject
    PgPool pgPool;

    private String rawApiKey;
    private UUID tenantId;

    @BeforeAll
    void setupTenantAndApiKey() {
        tenantId = UUID.randomUUID();
        var generated = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        rawApiKey = generated.rawKey();

        pgPool.preparedQuery("""
                        INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                            required_accounting, micro_enterprise_regime, environment,
                            emission_type, rate_limit_rpm, status, created_at, updated_at)
                        VALUES ($1, $2, $3, $4, $5, $6, true, false, 'test', 1, 100, 'active', now(), now())""")
                .execute(Tuple.of(tenantId, TENANT_RUC, "Profile Test S.A.", "Profile Test", "Guayaquil", TENANT_SCHEMA))
                .await().indefinitely();

        pgPool.preparedQuery("""
                        INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status, created_at)
                        VALUES ($1, $2, $3, $4, $5, '*', 'active', now())""")
                .execute(Tuple.of(UUID.randomUUID(), tenantId, generated.keyPrefix(), generated.hash(), "profile-key"))
                .await().indefinitely();

        pgPool.query("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA).execute()
                .await().indefinitely();
    }

    // ── GET /tenant/profile ──

    @Test
    @Order(1)
    void getProfile_returnsAuthenticatedTenant() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().get("/v1/tenant/profile")
                .then()
                .statusCode(200)
                .body("data.id", equalTo(tenantId.toString()))
                .body("data.ruc", equalTo(TENANT_RUC))
                .body("data.legal_name", equalTo("Profile Test S.A."))
                .body("data.trade_name", equalTo("Profile Test"))
                .body("data.main_address", equalTo("Guayaquil"))
                .body("data.environment", equalTo("test"))
                .body("data.schema_name", equalTo(TENANT_SCHEMA))
                .body("data.status", equalTo("active"))
                .body("data.required_accounting", is(true))
                .body("data.certificate.configured", is(false))
                .body("data.certificate.valid", is(false))
                .body("meta.request_id", notNullValue());
    }

    @Test
    @Order(2)
    void getProfile_withoutAuth_returns401() {
        RestAssured.given()
                .accept(ContentType.JSON)
                .when().get("/v1/tenant/profile")
                .then()
                .statusCode(401);
    }

    // ── PUT /tenant/profile ──

    @Test
    @Order(3)
    void updateProfile_updatesLegalName() {
        var body = """
                {
                  "legal_name": "Profile Updated S.A.",
                  "main_address": "Cuenca, Ecuador"
                }
                """;

        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/v1/tenant/profile")
                .then()
                .statusCode(200)
                .body("data.legal_name", equalTo("Profile Updated S.A."))
                .body("data.main_address", equalTo("Cuenca, Ecuador"))
                .body("data.ruc", equalTo(TENANT_RUC));
    }

    @Test
    @Order(4)
    void updateProfile_verifyChangePersisted() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().get("/v1/tenant/profile")
                .then()
                .statusCode(200)
                .body("data.legal_name", equalTo("Profile Updated S.A."))
                .body("data.main_address", equalTo("Cuenca, Ecuador"));
    }

    @Test
    @Order(5)
    void updateProfile_webhookConfig() {
        var body = """
                {
                  "webhook_url": "https://hooks.example.com/key49",
                  "webhook_secret": "wh_secret_123",
                  "email_sender_name": "Facturas Test",
                  "reply_email": "facturas@example.com"
                }
                """;

        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/v1/tenant/profile")
                .then()
                .statusCode(200)
                .body("data.webhook_url", equalTo("https://hooks.example.com/key49"))
                .body("data.email_sender_name", equalTo("Facturas Test"))
                .body("data.reply_email", equalTo("facturas@example.com"));
    }

    @Test
    @Order(6)
    void updateProfile_cannotChangeAdminFields() {
        // status and rateLimitRpm are admin-only; UpdateProfileRequest doesn't have them
        var body = """
                {
                  "legal_name": "Still Updated S.A."
                }
                """;

        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .contentType(ContentType.JSON)
                .body(body)
                .when().put("/v1/tenant/profile")
                .then()
                .statusCode(200)
                .body("data.status", equalTo("active"))
                .body("data.rate_limit_rpm", equalTo(100));
    }

    @Test
    @Order(7)
    void updateProfile_withoutAuth_returns401() {
        RestAssured.given()
                .contentType(ContentType.JSON)
                .body("{\"legal_name\": \"Hack\"}")
                .when().put("/v1/tenant/profile")
                .then()
                .statusCode(401);
    }

    // ── GET /tenant/certificate/status ──

    @Test
    @Order(8)
    void certificateStatus_noCertificate_returns422() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .accept(ContentType.JSON)
                .when().get("/v1/tenant/certificate/status")
                .then()
                .statusCode(422)
                .body("error.code", equalTo("CERTIFICATE_NOT_CONFIGURED"));
    }

    @Test
    @Order(9)
    void certificateStatus_withoutAuth_returns401() {
        RestAssured.given()
                .accept(ContentType.JSON)
                .when().get("/v1/tenant/certificate/status")
                .then()
                .statusCode(401);
    }
}
