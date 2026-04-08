package auracore.key49.api.filter;

import java.sql.SQLException;
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
import jakarta.inject.Inject;

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyAuthFilterTest {

    @Inject
    javax.sql.DataSource dataSource;

    private String validRawKey;

    @BeforeAll
    void setupTestData() throws SQLException {
        var generated = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        validRawKey = generated.rawKey();

        var tenantId = UUID.randomUUID();
        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("""
                    INSERT INTO tenants (tenant_id, ruc, legal_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, status, created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, false, false, 'test', 1, 10000, ?, now(), now())""")) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, "0190155722001");
                ps.setString(3, "Auth Test S.A.");
                ps.setString(4, "Quito");
                ps.setString(5, "tenant_auth_test");
                ps.setString(6, "active");
                ps.executeUpdate();
            }

            try (var ps = conn.prepareStatement("""
                    INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status, created_at)
                    VALUES (?::uuid, ?::uuid, ?, ?, ?, '*', ?, now())""")) {
                ps.setObject(1, UUID.randomUUID().toString());
                ps.setObject(2, tenantId.toString());
                ps.setString(3, generated.keyPrefix());
                ps.setString(4, generated.hash());
                ps.setString(5, "test-key");
                ps.setString(6, "active");
                ps.executeUpdate();
            }
        }
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
