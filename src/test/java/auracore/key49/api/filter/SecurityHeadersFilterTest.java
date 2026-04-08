package auracore.key49.api.filter;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;

@QuarkusTest
class SecurityHeadersFilterTest {

    // Usa /v1/invoices sin autenticación (401) para verificar que
    // los headers de seguridad se incluyen en TODAS las respuestas JAX-RS
    @Test
    void shouldIncludeXFrameOptionsDeny() {
        RestAssured.given()
                .when().get("/v1/invoices")
                .then()
                .header("X-Frame-Options", equalTo("DENY"));
    }

    @Test
    void shouldIncludeXContentTypeOptionsNosniff() {
        RestAssured.given()
                .when().get("/v1/invoices")
                .then()
                .header("X-Content-Type-Options", equalTo("nosniff"));
    }

    @Test
    void shouldIncludeReferrerPolicy() {
        RestAssured.given()
                .when().get("/v1/invoices")
                .then()
                .header("Referrer-Policy", equalTo("strict-origin-when-cross-origin"));
    }

    @Test
    void shouldIncludePermissionsPolicy() {
        RestAssured.given()
                .when().get("/v1/invoices")
                .then()
                .header("Permissions-Policy", equalTo("camera=(), microphone=(), geolocation=()"));
    }

    @Test
    void shouldIncludeContentSecurityPolicy() {
        RestAssured.given()
                .when().get("/v1/invoices")
                .then()
                .header("Content-Security-Policy", containsString("default-src 'self'"))
                .header("Content-Security-Policy", containsString("frame-ancestors 'none'"));
    }

    @Test
    void shouldIncludeAllSecurityHeadersOnErrorResponses() {
        RestAssured.given()
                .when().get("/v1/invoices")
                .then()
                .statusCode(401)
                .header("X-Frame-Options", equalTo("DENY"))
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("Referrer-Policy", equalTo("strict-origin-when-cross-origin"))
                .header("Permissions-Policy", equalTo("camera=(), microphone=(), geolocation=()"))
                .header("Content-Security-Policy", containsString("default-src 'self'"));
    }
}
