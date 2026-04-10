package auracore.key49.api.filter;

import java.sql.SQLException;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
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
import io.restassured.http.ContentType;
import io.vertx.mutiny.redis.client.Command;
import io.vertx.mutiny.redis.client.Redis;
import io.vertx.mutiny.redis.client.Request;
import jakarta.inject.Inject;

/**
 * Verifica que rate limiting granular aplica límites independientes para
 * operaciones de escritura (WRITE) y lectura (READ).
 */

@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GranularRateLimitTest {

    private static final String TENANT_SCHEMA = "tenant_granular_rl";
    private static final String TENANT_RUC = "1792146739001";
    private static final int WRITE_LIMIT = 3;
    private static final int READ_LIMIT = 6;

    @Inject
    javax.sql.DataSource dataSource;

    @Inject
    Redis redis;

    private String rawApiKey;
    private UUID tenantId;
    private String apiKeyPrefix;

    @BeforeAll
    void setup() throws SQLException {
        tenantId = UUID.randomUUID();
        var generated = ApiKeyService.generate(ApiKeyService.PREFIX_TEST);
        rawApiKey = generated.rawKey();
        apiKeyPrefix = generated.keyPrefix();

        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("""
                    INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                        required_accounting, micro_enterprise_regime, environment,
                        emission_type, rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm,
                        status, created_at, updated_at)
                    VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 100, ?, ?, 'active', now(), now())""")) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, TENANT_RUC);
                ps.setString(3, "Granular RL Test S.A.");
                ps.setString(4, "GranularRL");
                ps.setString(5, "Quito");
                ps.setString(6, TENANT_SCHEMA);
                ps.setInt(7, WRITE_LIMIT);
                ps.setInt(8, READ_LIMIT);
                ps.executeUpdate();
            }

            try (var ps = conn.prepareStatement("""
                    INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status, created_at)
                    VALUES (?::uuid, ?::uuid, ?, ?, ?, '*', 'active', now())""")) {
                ps.setObject(1, UUID.randomUUID().toString());
                ps.setObject(2, tenantId.toString());
                ps.setString(3, generated.keyPrefix());
                ps.setString(4, generated.hash());
                ps.setString(5, "granular-rl-key");
                ps.executeUpdate();
            }

            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA);
                stmt.execute("""
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
                        )""".formatted(TENANT_SCHEMA));
            }
        }

        clearRateLimitKeys();
    }

    private void clearRateLimitKeys() {
        redis.send(Request.cmd(Command.DEL).arg("ratelimit:" + apiKeyPrefix + ":read"))
                .await().indefinitely();
        redis.send(Request.cmd(Command.DEL).arg("ratelimit:" + apiKeyPrefix + ":write"))
                .await().indefinitely();
    }

    @Test
    @Order(1)
    void readRequestsShouldUseReadLimit() {
        clearRateLimitKeys();

        var response = RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .when()
                .get("/v1/metrics/summary")
                .then()
                .statusCode(200)
                .extract();

        org.junit.jupiter.api.Assertions.assertEquals(
                String.valueOf(READ_LIMIT),
                response.header("X-RateLimit-Limit"),
                "GET should use READ limit");
    }

    @Test
    @Order(2)
    void writeRequestsShouldUseWriteLimit() {
        clearRateLimitKeys();

        // POST to an endpoint that accepts POST — using invoices as a write endpoint
        // This will return 400 (bad request) but the rate limit headers should be present
        var response = RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/v1/invoices")
                .then()
                .extract();

        org.junit.jupiter.api.Assertions.assertEquals(
                String.valueOf(WRITE_LIMIT),
                response.header("X-RateLimit-Limit"),
                "POST should use WRITE limit");
    }

    @Test
    @Order(3)
    void writeLimitShouldNotAffectReadLimit() {
        clearRateLimitKeys();

        // Exhaust write limit (3 requests)
        for (int i = 0; i < WRITE_LIMIT; i++) {
            RestAssured.given()
                    .header("Authorization", "Bearer " + rawApiKey)
                    .contentType(ContentType.JSON)
                    .body("{}")
                    .when()
                    .post("/v1/invoices");
        }

        // Next write should be rejected
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/v1/invoices")
                .then()
                .statusCode(429)
                .body("error.code", equalTo("RATE_LIMIT_EXCEEDED"));

        // But reads should still work
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .when()
                .get("/v1/metrics/summary")
                .then()
                .statusCode(200)
                .header("X-RateLimit-Limit", String.valueOf(READ_LIMIT));
    }

    @Test
    @Order(4)
    void readLimitShouldNotAffectWriteLimit() {
        clearRateLimitKeys();

        // Exhaust read limit (6 requests)
        for (int i = 0; i < READ_LIMIT; i++) {
            RestAssured.given()
                    .header("Authorization", "Bearer " + rawApiKey)
                    .when()
                    .get("/v1/metrics/summary")
                    .then()
                    .statusCode(200);
        }

        // Next read should be rejected
        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .when()
                .get("/v1/metrics/summary")
                .then()
                .statusCode(429);

        // But writes should still work (not 429)
        var writeStatus = RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/v1/invoices")
                .then()
                .extract().statusCode();

        org.junit.jupiter.api.Assertions.assertNotEquals(429, writeStatus,
                "Write should not be rate limited when only read limit is exhausted");
    }

    @Test
    @Order(5)
    void rateLimitRejectionShouldIncludeRetryAfterHeader() {
        clearRateLimitKeys();

        // Exhaust write limit
        for (int i = 0; i <= WRITE_LIMIT; i++) {
            RestAssured.given()
                    .header("Authorization", "Bearer " + rawApiKey)
                    .contentType(ContentType.JSON)
                    .body("{}")
                    .when()
                    .post("/v1/invoices");
        }

        RestAssured.given()
                .header("Authorization", "Bearer " + rawApiKey)
                .contentType(ContentType.JSON)
                .body("{}")
                .when()
                .post("/v1/invoices")
                .then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .header("X-RateLimit-Remaining", "0");
    }
}
