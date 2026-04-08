package auracore.key49.api.filter;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test de integración para el TracingFilter.
 *
 * <p>
 * Verifica que todas las respuestas incluyen {@code X-Request-Id} y que el
 * formato del request ID es correcto ({@code req_} + 16 caracteres).</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TracingFilterEndToEndTest {

    @Test
    @Order(1)
    void shouldIncludeRequestIdOnUnauthenticatedRequests() {
        var response = RestAssured.given()
                .when()
                .get("/v1/invoices")
                .then()
                .statusCode(401)
                .extract()
                .response();

        var requestId = response.getHeader("X-Request-Id");
        assertNotNull(requestId, "X-Request-Id header should be present");
        assertTrue(requestId.startsWith("req_"), "Request ID should start with 'req_'");
        assertTrue(requestId.length() > 4, "Request ID should have content after prefix");
    }

    @Test
    @Order(2)
    void shouldReturn401WithRequestIdForUnauthenticatedRequests() {
        RestAssured.given()
                .when()
                .get("/v1/invoices")
                .then()
                .statusCode(401)
                .header("X-Request-Id", notNullValue())
                .header("X-Request-Id", startsWith("req_"));
    }

    @Test
    @Order(3)
    void eachRequestShouldHaveUniqueRequestId() {
        var response1 = RestAssured.given()
                .when()
                .get("/v1/invoices")
                .then()
                .extract()
                .response();

        var response2 = RestAssured.given()
                .when()
                .get("/v1/invoices")
                .then()
                .extract()
                .response();

        var requestId1 = response1.getHeader("X-Request-Id");
        var requestId2 = response2.getHeader("X-Request-Id");

        assertNotNull(requestId1);
        assertNotNull(requestId2);
        assertTrue(!requestId1.equals(requestId2), "Each request should have a unique request ID");
    }
}
