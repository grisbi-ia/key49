package auracore.key49.api.tenant;

import auracore.key49.core.tenant.TenantSchemaResolver;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test de integración que verifica el aislamiento de datos entre esquemas de tenant.
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
    PgPool pgPool;

    private static boolean schemasInitialized = false;

    @BeforeEach
    void setupSchemas() {
        if (schemasInitialized) {
            return;
        }
        pgPool.query("CREATE SCHEMA IF NOT EXISTS " + SCHEMA_A).execute()
                .chain(() -> pgPool.query("CREATE SCHEMA IF NOT EXISTS " + SCHEMA_B).execute())
                .chain(() -> pgPool.query("SET search_path TO " + SCHEMA_A).execute())
                .chain(() -> pgPool.query(TEST_TABLE_DDL).execute())
                .chain(() -> pgPool.query("SET search_path TO " + SCHEMA_B).execute())
                .chain(() -> pgPool.query(TEST_TABLE_DDL).execute())
                .chain(() -> pgPool.query("SET search_path TO public").execute())
                .await().indefinitely();

        schemasInitialized = true;
    }

    @Test
    void shouldIsolateDataBetweenTenantSchemas() {
        var searchPathA = TenantSchemaResolver.buildSearchPathSql(SCHEMA_A);
        var searchPathB = TenantSchemaResolver.buildSearchPathSql(SCHEMA_B);

        // Insert data in tenant_aaa
        pgPool.query(searchPathA).execute()
                .chain(() -> pgPool.query("INSERT INTO test_data (value) VALUES ('data_from_aaa')").execute())
                .await().indefinitely();

        // Insert data in tenant_bbb
        pgPool.query(searchPathB).execute()
                .chain(() -> pgPool.query("INSERT INTO test_data (value) VALUES ('data_from_bbb')").execute())
                .await().indefinitely();

        // Query tenant_aaa — should only see its own data
        pgPool.query(searchPathA).execute().await().indefinitely();
        var countA = pgPool.query("SELECT COUNT(*) FROM test_data WHERE value = 'data_from_aaa'").execute()
                .map(rows -> rows.iterator().next().getLong(0))
                .await().indefinitely();
        assertEquals(1L, countA, "tenant_aaa should see its own data");

        // Query tenant_aaa — should NOT see tenant_bbb's data
        var countAFromB = pgPool.query("SELECT COUNT(*) FROM test_data WHERE value = 'data_from_bbb'").execute()
                .map(rows -> rows.iterator().next().getLong(0))
                .await().indefinitely();
        assertEquals(0L, countAFromB, "tenant_aaa should NOT see tenant_bbb's data");

        // Query tenant_bbb — should only see its own data
        pgPool.query(searchPathB).execute().await().indefinitely();
        var countB = pgPool.query("SELECT COUNT(*) FROM test_data WHERE value = 'data_from_bbb'").execute()
                .map(rows -> rows.iterator().next().getLong(0))
                .await().indefinitely();
        assertEquals(1L, countB, "tenant_bbb should see its own data");

        // Query tenant_bbb — should NOT see tenant_aaa's data
        var countBFromA = pgPool.query("SELECT COUNT(*) FROM test_data WHERE value = 'data_from_aaa'").execute()
                .map(rows -> rows.iterator().next().getLong(0))
                .await().indefinitely();
        assertEquals(0L, countBFromA, "tenant_bbb should NOT see tenant_aaa's data");
    }

    @Test
    void shouldSwitchBetweenTenantsCorrectly() {
        var searchPathA = TenantSchemaResolver.buildSearchPathSql(SCHEMA_A);
        var searchPathB = TenantSchemaResolver.buildSearchPathSql(SCHEMA_B);

        // Clean previous data
        pgPool.query(searchPathA).execute()
                .chain(() -> pgPool.query("DELETE FROM test_data WHERE value LIKE 'switch_%'").execute())
                .await().indefinitely();
        pgPool.query(searchPathB).execute()
                .chain(() -> pgPool.query("DELETE FROM test_data WHERE value LIKE 'switch_%'").execute())
                .await().indefinitely();

        // Insert into A, switch to B, insert into B
        pgPool.query(searchPathA).execute()
                .chain(() -> pgPool.query("INSERT INTO test_data (value) VALUES ('switch_a')").execute())
                .await().indefinitely();

        pgPool.query(searchPathB).execute()
                .chain(() -> pgPool.query("INSERT INTO test_data (value) VALUES ('switch_b')").execute())
                .await().indefinitely();

        // Verify isolation after switching
        pgPool.query(searchPathA).execute().await().indefinitely();
        var totalA = pgPool.query("SELECT COUNT(*) FROM test_data WHERE value LIKE 'switch_%'").execute()
                .map(rows -> rows.iterator().next().getLong(0))
                .await().indefinitely();

        pgPool.query(searchPathB).execute().await().indefinitely();
        var totalB = pgPool.query("SELECT COUNT(*) FROM test_data WHERE value LIKE 'switch_%'").execute()
                .map(rows -> rows.iterator().next().getLong(0))
                .await().indefinitely();

        assertEquals(1L, totalA, "tenant_aaa should have exactly 1 switch record");
        assertEquals(1L, totalB, "tenant_bbb should have exactly 1 switch record");
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
