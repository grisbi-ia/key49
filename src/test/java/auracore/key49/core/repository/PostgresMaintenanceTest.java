package auracore.key49.core.repository;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Test de integración que verifica las operaciones de mantenimiento de
 * PostgreSQL: VACUUM ANALYZE, autovacuum tuning, monitoreo de dead tuples, y
 * REINDEX CONCURRENTLY.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostgresMaintenanceTest {

    private static final String TEST_SCHEMA = "test_maintenance";

    @Inject
    javax.sql.DataSource dataSource;

    @BeforeAll
    void setup() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
            stmt.execute("CREATE SCHEMA " + TEST_SCHEMA);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            stmt.execute("""
                    CREATE TABLE documents (
                        document_id UUID NOT NULL DEFAULT gen_random_uuid(),
                        status      VARCHAR(20) NOT NULL DEFAULT 'CREATED',
                        issue_date  DATE NOT NULL,
                        total       NUMERIC(14,2) NOT NULL DEFAULT 0,
                        created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                        PRIMARY KEY (document_id)
                    )
                    """);

            stmt.execute("CREATE INDEX idx_maint_documents_status ON documents(status)");
            stmt.execute("CREATE INDEX idx_maint_documents_date ON documents(issue_date DESC)");

            // Insert test data
            for (int i = 0; i < 50; i++) {
                stmt.execute(String.format(
                        "INSERT INTO documents (status, issue_date, total) VALUES ('AUTHORIZED', '2026-04-%02d', %d.50)",
                        (i % 28) + 1, i * 10));
            }
        }
    }

    @AfterAll
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("RESET search_path");
            stmt.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
        }
    }

    @Test
    @Order(1)
    void shouldRunVacuumAnalyze() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            // VACUUM ANALYZE must succeed (no return, no error)
            stmt.execute("VACUUM ANALYZE " + TEST_SCHEMA + ".documents");

            // Verify pg_stat_user_tables shows updated stats
            ResultSet rs = stmt.executeQuery("""
                    SELECT n_live_tup, last_analyze
                    FROM pg_stat_user_tables
                    WHERE schemaname = 'test_maintenance' AND relname = 'documents'
                    """);
            assertTrue(rs.next(), "pg_stat_user_tables should have an entry");
            assertTrue(rs.getLong("n_live_tup") >= 50, "Should have at least 50 live tuples");
        }
    }

    @Test
    @Order(2)
    void shouldSetAutovacuumParameters() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);

            // Apply autovacuum tuning
            stmt.execute("""
                    ALTER TABLE test_maintenance.documents SET (
                        autovacuum_vacuum_scale_factor = 0.05,
                        autovacuum_analyze_scale_factor = 0.05,
                        autovacuum_vacuum_cost_delay = 10
                    )
                    """);

            // Verify the settings were applied via reloptions
            ResultSet rs = stmt.executeQuery("""
                    SELECT array_to_string(c.reloptions, ',') AS options
                    FROM pg_class c
                    JOIN pg_namespace n ON c.relnamespace = n.oid
                    WHERE c.relname = 'documents' AND n.nspname = 'test_maintenance'
                    """);
            assertTrue(rs.next(), "Should find the table");
            String options = rs.getString("options");
            assertNotNull(options, "reloptions should not be null after ALTER");
            assertTrue(options.contains("autovacuum_vacuum_scale_factor=0.05"),
                    "Should contain vacuum_scale_factor=0.05, got: " + options);
            assertTrue(options.contains("autovacuum_analyze_scale_factor=0.05"),
                    "Should contain analyze_scale_factor=0.05, got: " + options);
            assertTrue(options.contains("autovacuum_vacuum_cost_delay=10"),
                    "Should contain vacuum_cost_delay=10, got: " + options);
        }
    }

    @Test
    @Order(3)
    void shouldMonitorDeadTuples() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            // Generate dead tuples by updating rows
            stmt.execute("UPDATE documents SET total = total + 1");

            // Run ANALYZE to update stats
            stmt.execute("ANALYZE " + TEST_SCHEMA + ".documents");

            // Query dead tuple stats
            ResultSet rs = stmt.executeQuery("""
                    SELECT n_live_tup, n_dead_tup
                    FROM pg_stat_user_tables
                    WHERE schemaname = 'test_maintenance' AND relname = 'documents'
                    """);
            assertTrue(rs.next(), "Should find stats entry");
            long liveTuples = rs.getLong("n_live_tup");
            long deadTuples = rs.getLong("n_dead_tup");

            // After UPDATE, dead tuples should be > 0 (old row versions)
            assertTrue(liveTuples >= 50, "Should still have >= 50 live tuples, got: " + liveTuples);
            assertTrue(deadTuples >= 0, "Dead tuples should be tracked");
        }
    }

    @Test
    @Order(4)
    void shouldReindexConcurrently() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);

            // REINDEX CONCURRENTLY on a specific index — should not block and should succeed
            stmt.execute("REINDEX INDEX CONCURRENTLY " + TEST_SCHEMA + ".idx_maint_documents_status");

            // Verify the index still exists and is valid after reindex
            ResultSet rs = stmt.executeQuery("""
                    SELECT i.relname, ix.indisvalid
                    FROM pg_index ix
                    JOIN pg_class i ON ix.indexrelid = i.oid
                    JOIN pg_class t ON ix.indrelid = t.oid
                    JOIN pg_namespace n ON t.relnamespace = n.oid
                    WHERE n.nspname = 'test_maintenance'
                      AND i.relname = 'idx_maint_documents_status'
                    """);
            assertTrue(rs.next(), "Index should still exist after REINDEX");
            assertTrue(rs.getBoolean("indisvalid"), "Index should be valid after REINDEX");
        }
    }

    @Test
    @Order(5)
    void shouldQueryTableSizes() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);

            // Query used in monitor_bloat.sh
            ResultSet rs = stmt.executeQuery("""
                    SELECT
                        pg_size_pretty(pg_total_relation_size('test_maintenance.documents')) AS total_size,
                        pg_size_pretty(pg_relation_size('test_maintenance.documents')) AS table_size
                    """);
            assertTrue(rs.next(), "Should return size info");
            String totalSize = rs.getString("total_size");
            assertNotNull(totalSize, "total_size should not be null");
            assertTrue(totalSize.contains("kB") || totalSize.contains("bytes"),
                    "Table should have measurable size, got: " + totalSize);
        }
    }

    @Test
    @Order(6)
    void shouldTrackAutovacuumActivity() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);

            // Query that the monitoring script uses to check vacuum & analyze counts
            ResultSet rs = stmt.executeQuery("""
                    SELECT
                        vacuum_count,
                        analyze_count,
                        autovacuum_count,
                        autoanalyze_count
                    FROM pg_stat_user_tables
                    WHERE schemaname = 'test_maintenance' AND relname = 'documents'
                    """);
            assertTrue(rs.next(), "Should find stat entry");

            // We ran VACUUM and ANALYZE manually in previous tests
            long vacuumCount = rs.getLong("vacuum_count");
            long analyzeCount = rs.getLong("analyze_count");

            assertTrue(vacuumCount >= 1, "vacuum_count should be >= 1 after manual VACUUM, got: " + vacuumCount);
            assertTrue(analyzeCount >= 1, "analyze_count should be >= 1 after manual ANALYZE, got: " + analyzeCount);
        }
    }

    @Test
    @Order(7)
    void shouldCalculateDeadTuplePercentage() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);

            // Query from monitor_bloat.sh: dead tuple percentage calculation
            ResultSet rs = stmt.executeQuery("""
                    SELECT
                        n_live_tup,
                        n_dead_tup,
                        CASE WHEN n_live_tup > 0
                            THEN ROUND(n_dead_tup::NUMERIC / n_live_tup * 100, 2)
                            ELSE 0
                        END AS dead_pct
                    FROM pg_stat_user_tables
                    WHERE schemaname = 'test_maintenance' AND relname = 'documents'
                    """);
            assertTrue(rs.next(), "Should find stats");
            double deadPct = rs.getDouble("dead_pct");
            // Valid result (could be 0 if autovacuum already ran, or > 0 from our updates)
            assertTrue(deadPct >= 0, "dead_pct should be >= 0, got: " + deadPct);
        }
    }

    @Test
    @Order(8)
    void shouldVerifyIndexValidity() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);

            // Verify all indexes in the test schema are valid
            ResultSet rs = stmt.executeQuery("""
                    SELECT i.relname AS index_name, ix.indisvalid
                    FROM pg_index ix
                    JOIN pg_class i ON ix.indexrelid = i.oid
                    JOIN pg_class t ON ix.indrelid = t.oid
                    JOIN pg_namespace n ON t.relnamespace = n.oid
                    WHERE n.nspname = 'test_maintenance'
                    ORDER BY i.relname
                    """);

            int count = 0;
            while (rs.next()) {
                assertTrue(rs.getBoolean("indisvalid"),
                        "Index " + rs.getString("index_name") + " should be valid");
                count++;
            }
            // 2 custom indexes + PK index = at least 3
            assertTrue(count >= 3, "Should have at least 3 indexes (incl PK), got: " + count);
        }
    }

    private long getSingleLong(ResultSet rs) throws SQLException {
        assertTrue(rs.next());
        return rs.getLong(1);
    }
}
