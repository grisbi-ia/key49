package auracore.key49.api.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import auracore.key49.core.tenant.TenantSchemaResolver;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import java.sql.SQLException;

/**
 * Test de integracion que verifica el aislamiento de datos entre esquemas de tenant.
 * Usa DevServices PostgreSQL (Testcontainers auto-configurado por Quarkus).
 */
@QuarkusTest
class TenantSchemaIsolationTest {

    private static final String SCHEMA_A = "tenant_aaa";
    private static final String SCHEMA_B = "tenant_bbb";
    private static final String TEST_TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS test_data (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                value VARCHAR(100) NOT NULL
            )
            """;

    @Inject
    javax.sql.DataSource dataSource;

    private static boolean schemasInitialized = false;

    @BeforeEach
    void setupSchemas() throws SQLException {
        if (schemasInitialized) {
            return;
        }
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA_A);
            stmt.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA_B);
            stmt.execute("SET search_path TO " + SCHEMA_A);
            stmt.execute(TEST_TABLE_DDL);
            stmt.execute("SET search_path TO " + SCHEMA_B);
            stmt.execute(TEST_TABLE_DDL);
            stmt.execute("SET search_path TO public");
        }
        schemasInitialized = true;
    }

    @Test
    void shouldIsolateDataBetweenTenantSchemas() throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {

            // Insert data in tenant_aaa
            stmt.execute(TenantSchemaResolver.buildSearchPathSql(SCHEMA_A));
            stmt.execute("INSERT INTO test_data (value) VALUES ('data_from_aaa')");

            // Insert data in tenant_bbb
            stmt.execute(TenantSchemaResolver.buildSearchPathSql(SCHEMA_B));
            stmt.execute("INSERT INTO test_data (value) VALUES ('data_from_bbb')");

            // Query tenant_aaa
            stmt.execute(TenantSchemaResolver.buildSearchPathSql(SCHEMA_A));
            try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM test_data WHERE value = 'data_from_aaa'")) {
                rs.next();
                assertEquals(1L, rs.getLong(1), "tenant_aaa should see its own data");
            }

            try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM test_data WHERE value = 'data_from_bbb'")) {
                rs.next();
                assertEquals(0L, rs.getLong(1), "tenant_aaa should NOT see tenant_bbb's data");
            }

            // Query tenant_bbb
            stmt.execute(TenantSchemaResolver.buildSearchPathSql(SCHEMA_B));
            try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM test_data WHERE value = 'data_from_bbb'")) {
                rs.next();
                assertEquals(1L, rs.getLong(1), "tenant_bbb should see its own data");
            }

            try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM test_data WHERE value = 'data_from_aaa'")) {
                rs.next();
                assertEquals(0L, rs.getLong(1), "tenant_bbb should NOT see tenant_aaa's data");
            }
        }
    }

    @Test
    void shouldSwitchBetweenTenantsCorrectly() throws SQLException {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {

            // Clean previous data
            stmt.execute(TenantSchemaResolver.buildSearchPathSql(SCHEMA_A));
            stmt.execute("DELETE FROM test_data WHERE value LIKE 'switch_%'");
            stmt.execute(TenantSchemaResolver.buildSearchPathSql(SCHEMA_B));
            stmt.execute("DELETE FROM test_data WHERE value LIKE 'switch_%'");

            // Insert into A, switch to B, insert into B
            stmt.execute(TenantSchemaResolver.buildSearchPathSql(SCHEMA_A));
            stmt.execute("INSERT INTO test_data (value) VALUES ('switch_a')");
            stmt.execute(TenantSchemaResolver.buildSearchPathSql(SCHEMA_B));
            stmt.execute("INSERT INTO test_data (value) VALUES ('switch_b')");

            // Verify isolation after switching
            stmt.execute(TenantSchemaResolver.buildSearchPathSql(SCHEMA_A));
            try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM test_data WHERE value LIKE 'switch_%'")) {
                rs.next();
                assertEquals(1L, rs.getLong(1), "tenant_aaa should have exactly 1 switch record");
            }

            stmt.execute(TenantSchemaResolver.buildSearchPathSql(SCHEMA_B));
            try (var rs = stmt.executeQuery("SELECT COUNT(*) FROM test_data WHERE value LIKE 'switch_%'")) {
                rs.next();
                assertEquals(1L, rs.getLong(1), "tenant_bbb should have exactly 1 switch record");
            }
        }
    }

    @Test
    void shouldRejectInvalidSchemaName() {
        assertThrows(IllegalArgumentException.class, () ->
                TenantSchemaResolver.buildSearchPathSql("'; DROP TABLE tenants;--")
        );
    }

    @Test
    void shouldBuildCorrectSearchPathSql() {
        var sql = TenantSchemaResolver.buildSearchPathSql("tenant_abc123");
        assertEquals("SET search_path TO 'tenant_abc123', public", sql);
    }
}
