package auracore.key49.api.filter;

import java.util.UUID;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import auracore.key49.core.service.ApiKeyService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyAuthFilterTest {

    @Inject
    PgPool pgPool;

    private String validRawKey;

    @BeforeAll
    void setupTestData() {
        var generated = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        validRawKey = generated.rawKey();

        // Insert tenant via raw SQL (no Vert.x context needed with PgPool)
        var tenantId = UUID.randomUUID();
        pgPool.preparedQuery("""
                        INSERT INTO tenants (tenant_id, ruc, legal_name, main_address, schema_name,
                            required_accounting, micro_enterprise_regime, environment,
                            emission_type, rate_limit_rpm, status, created_at, updated_at)
                        VALUES ($1, $2, $3, $4, $5, false, false, 'test', 1, 100, $6, now(), now())""")
                .execute(Tuple.of(tenantId, "0190155722001", "Auth Test S.A.", "Quito", "tenant_auth_test", "active"))
                .await().indefinitely();

        // Insert API key
        pgPool.preparedQuery("""
                        INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status, created_at)
                        VALUES ($1, $2, $3, $4, $5, '*', $6, now())""")
                .execute(Tuple.of(UUID.randomUUID(), tenantId, generated.keyPrefix(), generated.hash(), "test-key", "active"))
                .await().indefinitely();
    }

    @Test
    void shouldRejectRequestWithoutAuthHeader() {
        RestAssured.given()
                .when().get("/auth-test")
                .then()
                .statusCode(401)
                .body("error.code", equalTo("AUTHENTICATION_ERROR"));
    }

    @Test
    void shouldRejectInvalidBearerToken() {
        RestAssured.given()
                .header("Authorization", "Bearer fec_test_invalidkeyxxxxxxxx")
                .when().get("/auth-test")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldRejectMalformedAuthHeader() {
        RestAssured.given()
                .header("Authorization", "Basic dXNlcjpwYXNz")
                .when().get("/auth-test")
                .then()
                .statusCode(401);
    }

    @Test
    void shouldAllowHealthEndpointWithoutAuth() {
        RestAssured.given()
                .when().get("/q/health")
                .then()
                .statusCode(anyOf(is(200), is(503))); // health check may fail in test but should not be 401
    }

    @Test
    void shouldAuthenticateWithValidKey() {
        RestAssured.given()
                .header("Authorization", "Bearer " + validRawKey)
                .when().get("/auth-test")
                .then()
                .statusCode(200)
                .body(equalTo("ok"));
    }
}
