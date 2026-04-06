package auracore.key49.api.filter;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import auracore.key49.core.service.ApiKeyService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;

/**
 * Test de integración para rate limiting con Redis.
 *
 * <p>
 * Crea un tenant con rate_limit_rpm bajo (5 req/min) y verifica que al exceder
 * el límite se retorne 429 con los headers correctos.</p>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RateLimitEndToEndTest {

    private static final String TENANT_SCHEMA = "tenant_ratelimit_e2e";
    private static final String TENANT_RUC = "1792146739001";
    private static final int RATE_LIMIT = 5;

    @Inject
    PgPool pgPool;

    @Inject
    Redis redis;

    private String rawApiKey;
    private UUID tenantId;
    private String apiKeyPrefix;

    @BeforeAll
    void setup() {
        tenantId = UUID.randomUUID();
        var generated = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        rawApiKey = generated.rawKey();
        apiKeyPrefix = generated.keyPrefix();

        var certExpiration = Instant.now().plus(90, ChronoUnit.DAYS);

        // Insert tenant with low rate limit
        pgPool.preparedQuery("""
                        INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                            required_accounting, micro_enterprise_regime, environment,
                            emission_type, rate_limit_rpm, status, created_at, updated_at)
                        VALUES ($1, $2, $3, $4, $5, $6, false, false, 'test', 1, $7, 'active', now(), now())""")
                .execute(Tuple.tuple().addUUID(tenantId).addString(TENANT_RUC)
                        .addString("Rate Limit Test S.A.").addString("RateLimit")
                        .addString("Quito").addString(TENANT_SCHEMA)
                        .addInteger(RATE_LIMIT))
                .await().indefinitely();

        // Insert API key
        pgPool.preparedQuery("""
                        INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status, created_at)
                        VALUES ($1, $2, $3, $4, $5, '*', 'active', now())""")
                .execute(Tuple.of(UUID.randomUUID(), tenantId, generated.keyPrefix(), generated.hash(), "ratelimit-key"))
                .await().indefinitely();

        // Create tenant schema (needed for metrics endpoint)
        pgPool.query("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA).execute()
                .chain(() -> pgPool.query("""
                        CREATE TABLE IF NOT EXISTS %s.documents (
                            document_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            document_type VARCHAR(3) NOT NULL DEFAULT '01',
                            status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
                            access_key VARCHAR(49),
                            establishment VARCHAR(3) NOT NULL DEFAULT '001',
                            issue_point VARCHAR(3) NOT NULL DEFAULT '001',
                            sequence_number VARCHAR(9) NOT NULL DEFAULT '000000001',
                            issue_date DATE NOT NULL DEFAULT CURRENT_DATE,
                            recipient_id VARCHAR(13),
                            recipient_name VARCHAR(300),
                            total_without_tax NUMERIC(14,2) DEFAULT 0,
                            total_discount NUMERIC(14,2) DEFAULT 0,
                            tip NUMERIC(14,2) DEFAULT 0,
                            total_amount NUMERIC(14,2) DEFAULT 0,
                            request_payload JSONB,
                            original_xml TEXT,
                            authorization_number VARCHAR(49),
                            authorization_date TIMESTAMP WITH TIME ZONE,
                            sri_received_date TIMESTAMP WITH TIME ZONE,
                            sri_response_messages JSONB,
                            sri_submission_date TIMESTAMP WITH TIME ZONE,
                            retry_count INT DEFAULT 0,
                            next_retry_at TIMESTAMP WITH TIME ZONE,
                            email_sent_at TIMESTAMP WITH TIME ZONE,
                            email_status VARCHAR(20),
                            request_origin VARCHAR(20) DEFAULT 'API',
                            idempotency_key VARCHAR(100),
                            void_reason VARCHAR(500),
                            voided_at TIMESTAMP WITH TIME ZONE,
                            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                            updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                            UNIQUE (document_type, establishment, issue_point, sequence_number)
                        )""".formatted(TENANT_SCHEMA)).execute())
                .await().indefinitely();

        // Clear Redis rate limit keys for this test
        redis.send(Request.cmd(Command.DEL).arg("ratelimit:" + apiKeyPrefix))
                .await().indefinitely();
    }

    @Test
    @Order(1)
    void shouldIncludeRateLimitHeadersOnNormalRequest() {
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .when()
                .get("/v1/metrics/summary")
                .then()
                .statusCode(200)
                .header("X-RateLimit-Limit", String.valueOf(RATE_LIMIT))
                .header("X-RateLimit-Remaining", notNullValue())
                .header("X-RateLimit-Reset", notNullValue());
    }

    @Test
    @Order(2)
    void shouldReturn429WhenRateLimitExceeded() {
        // Clear Redis before this test to ensure clean state
        redis.send(Request.cmd(Command.DEL).arg("ratelimit:" + apiKeyPrefix))
                .await().indefinitely();

        // First, send enough requests to exhaust the limit
        for (int i = 0; i < RATE_LIMIT; i++) {
            RestAssured.given()
                    .header("Authorization", "Bearer " + rawApiKey)
                    .when()
                    .get("/v1/metrics/summary")
                    .then()
                    .statusCode(200);
        }

        // The next request should be rate limited
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .when()
                .get("/v1/metrics/summary")
                .then()
                .statusCode(429)
                .header("X-RateLimit-Limit", String.valueOf(RATE_LIMIT))
                .header("X-RateLimit-Remaining", "0")
                .header("Retry-After", notNullValue())
                .body("error.code", equalTo("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    @Order(3)
    void shouldNotRateLimitPublicPaths() {
        // Public paths (e.g., health check) should not be rate limited (not 429)
        int status = RestAssured.given()
                .when()
                .get("/q/health")
                .then()
                .extract().statusCode();

        // Health may return 200 or 503 depending on service status, but never 429
        org.junit.jupiter.api.Assertions.assertNotEquals(429, status,
                "Public path should not be rate limited");
    }

    @Test
    @Order(4)
    void shouldNotRateLimitUnauthenticatedRequests() {
        // Request without auth header should get 401, not 429
        RestAssured.given()
                .when()
                .get("/v1/metrics/summary")
                .then()
                .statusCode(401);
    }

    @Test
    @Order(5)
    void rateLimitHeadersShouldBeNumeric() {
        // Clear Redis for clean test
        redis.send(Request.cmd(Command.DEL).arg("ratelimit:" + apiKeyPrefix))
                .await().indefinitely();

        var response = RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .when()
                .get("/v1/metrics/summary")
                .then()
                .statusCode(200)
                .extract();

        var limitStr = response.header("X-RateLimit-Limit");
        var remainingStr = response.header("X-RateLimit-Remaining");
        var resetStr = response.header("X-RateLimit-Reset");

        // All should be parseable as integers
        int limit = Integer.parseInt(limitStr);
        long remaining = Long.parseLong(remainingStr);
        long reset = Long.parseLong(resetStr);

        org.junit.jupiter.api.Assertions.assertEquals(RATE_LIMIT, limit);
        org.junit.jupiter.api.Assertions.assertTrue(remaining >= 0 && remaining <= RATE_LIMIT);
        org.junit.jupiter.api.Assertions.assertTrue(reset > 0);
    }
}
