package auracore.key49.api.filter;

import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

@QuarkusTest
class AdminAuthFilterTest {

    private static final String VALID_TOKEN = "test-admin-token";

    @Test
    @DisplayName("rechaza request sin X-Admin-Token")
    void rejectsWithoutAdminToken() {
        RestAssured.given()
                .when().get("/v1/admin/test")
                .then()
                .statusCode(403)
                .body("error.code", equalTo("ADMIN_AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("rechaza request con token inválido")
    void rejectsInvalidToken() {
        RestAssured.given()
                .header("X-Admin-Token", "wrong-token")
                .when().get("/v1/admin/test")
                .then()
                .statusCode(403)
                .body("error.code", equalTo("ADMIN_AUTH_REQUIRED"));
    }

    @Test
    @DisplayName("permite request con token válido")
    void allowsValidToken() {
        RestAssured.given()
                .header("X-Admin-Token", VALID_TOKEN)
                .when().get("/v1/admin/test")
                .then()
                .statusCode(200)
                .body(equalTo("admin-ok"));
    }

    @Test
    @DisplayName("no afecta endpoints fuera de /v1/admin/")
    void doesNotAffectNonAdminPaths() {
        RestAssured.given()
                .when().get("/q/health")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(200),
                        org.hamcrest.Matchers.is(503)));
    }
}
