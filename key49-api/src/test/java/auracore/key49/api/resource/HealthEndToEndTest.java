package auracore.key49.api.resource;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test de integración para los health check endpoints.
 *
 * <p>Verifica que los endpoints {@code /q/health}, {@code /q/health/ready} y
 * {@code /q/health/live} están disponibles y retornan datos estructurados.</p>
 */
@QuarkusTest
class HealthEndToEndTest {

    @Test
    void healthReadyShouldReturnJson() {
        var response = RestAssured.given()
                .when()
                .get("/q/health/ready")
                .then()
                .extract()
                .response();

        // Status may be UP or DOWN depending on test infrastructure
        var status = response.jsonPath().getString("status");
        assertNotNull(status, "Health response should include status");
        assertTrue(status.equals("UP") || status.equals("DOWN"), "Status should be UP or DOWN");

        // Should include checks array
        var checks = response.jsonPath().getList("checks");
        assertNotNull(checks, "Health response should include checks");
        assertTrue(!checks.isEmpty(), "Should have at least one health check");
    }

    @Test
    void healthLiveShouldReturnJson() {
        var response = RestAssured.given()
                .when()
                .get("/q/health/live")
                .then()
                .extract()
                .response();

        var status = response.jsonPath().getString("status");
        assertNotNull(status, "Liveness response should include status");
    }

    @Test
    void healthReadyShouldIncludeMinioCheck() {
        var response = RestAssured.given()
                .when()
                .get("/q/health/ready")
                .then()
                .extract()
                .response();

        var checks = response.jsonPath().getList("checks.name");
        assertNotNull(checks);
        assertTrue(checks.contains("MinIO bucket"), "Should include MinIO health check");
    }

    @Test
    void healthReadyShouldIncludeCertificateCheck() {
        var response = RestAssured.given()
                .when()
                .get("/q/health/ready")
                .then()
                .extract()
                .response();

        var checks = response.jsonPath().getList("checks.name");
        assertNotNull(checks);
        assertTrue(checks.contains("Certificate expiration"), "Should include Certificate expiration check");
    }

    @Test
    void healthLiveShouldIncludeSriChecks() {
        var response = RestAssured.given()
                .when()
                .get("/q/health/live")
                .then()
                .extract()
                .response();

        var checks = response.jsonPath().getList("checks.name");
        assertNotNull(checks);
        assertTrue(checks.contains("SRI Recepción"), "Should include SRI Recepción check");
        assertTrue(checks.contains("SRI Autorización"), "Should include SRI Autorización check");
    }
}
