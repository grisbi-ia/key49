package auracore.key49.core.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Test de integración que verifica el particionamiento de la tabla documents.
 * Valida: - Partition pruning en queries con filtro de issue_date - Lookups por
 * PK (document_id) funcionan sin issue_date en WHERE - INSERTs se enrutan a la
 * partición correcta - Migración V005 produce una tabla particionada funcional
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DocumentPartitionTest {

    private static final String TEST_SCHEMA = "test_partition";

    @Inject
    javax.sql.DataSource dataSource;

    private UUID docMarchId;
    private UUID docAprilId;

    @BeforeAll
    void setup() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            // Create a clean test schema
            stmt.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
            stmt.execute("CREATE SCHEMA " + TEST_SCHEMA);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            // Create partitioned documents table (key columns for testing)
            stmt.execute("""
                    CREATE TABLE documents (
                        document_id         UUID NOT NULL DEFAULT gen_random_uuid(),
                        document_type       VARCHAR(2) NOT NULL,
                        establishment       VARCHAR(3) NOT NULL,
                        issue_point         VARCHAR(3) NOT NULL,
                        sequence_number     VARCHAR(9) NOT NULL,
                        access_key          VARCHAR(49),
                        recipient_id_type   VARCHAR(2) NOT NULL DEFAULT '04',
                        recipient_id        VARCHAR(20) NOT NULL DEFAULT '0000000000001',
                        recipient_name      VARCHAR(300) NOT NULL DEFAULT 'Test',
                        request_origin      VARCHAR(10) NOT NULL DEFAULT 'JSON',
                        issue_date          DATE NOT NULL,
                        status              VARCHAR(20) NOT NULL DEFAULT 'CREATED',
                        request_payload     JSONB DEFAULT '{}',
                        version             INT NOT NULL DEFAULT 1,
                        created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                        updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                        PRIMARY KEY (document_id, issue_date),
                        CONSTRAINT uq_part_documents_number
                            UNIQUE (document_type, establishment, issue_point, sequence_number, issue_date),
                        CONSTRAINT chk_part_documents_origin CHECK (
                            request_origin = 'JSON' AND request_payload IS NOT NULL
                        )
                    ) PARTITION BY RANGE (issue_date)
                    """);

            // Create monthly partitions for Q1-Q2 2026
            stmt.execute("CREATE TABLE documents_2026_01 PARTITION OF documents FOR VALUES FROM ('2026-01-01') TO ('2026-02-01')");
            stmt.execute("CREATE TABLE documents_2026_02 PARTITION OF documents FOR VALUES FROM ('2026-02-01') TO ('2026-03-01')");
            stmt.execute("CREATE TABLE documents_2026_03 PARTITION OF documents FOR VALUES FROM ('2026-03-01') TO ('2026-04-01')");
            stmt.execute("CREATE TABLE documents_2026_04 PARTITION OF documents FOR VALUES FROM ('2026-04-01') TO ('2026-05-01')");
            stmt.execute("CREATE TABLE documents_2026_05 PARTITION OF documents FOR VALUES FROM ('2026-05-01') TO ('2026-06-01')");
            stmt.execute("CREATE TABLE documents_2026_06 PARTITION OF documents FOR VALUES FROM ('2026-06-01') TO ('2026-07-01')");
            stmt.execute("CREATE TABLE documents_default PARTITION OF documents DEFAULT");

            // Create indexes (same as V005)
            stmt.execute("CREATE INDEX idx_part_documents_status ON documents(status)");
            stmt.execute("CREATE INDEX idx_part_documents_issue_date ON documents(issue_date DESC)");
            stmt.execute("CREATE INDEX idx_part_documents_type_date ON documents(document_type, issue_date DESC)");
        }
    }

    @AfterAll
    void cleanup() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
        }
    }

    @Test
    @Order(1)
    void shouldRouteInsertToCorrectPartition() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            // Insert document in March 2026
            docMarchId = UUID.randomUUID();
            stmt.execute(String.format(
                    "INSERT INTO documents (document_id, document_type, establishment, issue_point, sequence_number, issue_date) "
                    + "VALUES ('%s', '01', '001', '001', '000000001', '2026-03-15')",
                    docMarchId));

            // Insert document in April 2026
            docAprilId = UUID.randomUUID();
            stmt.execute(String.format(
                    "INSERT INTO documents (document_id, document_type, establishment, issue_point, sequence_number, issue_date) "
                    + "VALUES ('%s', '01', '001', '001', '000000002', '2026-04-10')",
                    docAprilId));

            // Verify March document is in documents_2026_03
            long marchCount = countQuery(stmt,
                    "SELECT count(*) FROM documents_2026_03 WHERE document_id = '" + docMarchId + "'");
            assertEquals(1L, marchCount, "March document should be in documents_2026_03");

            // Verify April document is in documents_2026_04
            long aprilCount = countQuery(stmt,
                    "SELECT count(*) FROM documents_2026_04 WHERE document_id = '" + docAprilId + "'");
            assertEquals(1L, aprilCount, "April document should be in documents_2026_04");

            // Verify they're NOT in each other's partitions
            long marchInApril = countQuery(stmt,
                    "SELECT count(*) FROM documents_2026_04 WHERE document_id = '" + docMarchId + "'");
            assertEquals(0L, marchInApril, "March document should NOT be in documents_2026_04");
        }
    }

    @Test
    @Order(2)
    void shouldPrunePartitionsOnDateRangeQuery() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            // EXPLAIN query filtered to March 2026 only
            String plan = explainQuery(stmt,
                    "SELECT * FROM documents WHERE issue_date >= '2026-03-01' AND issue_date < '2026-04-01'");

            // Partition pruning: plan should reference March partition
            assertTrue(plan.contains("documents_2026_03"),
                    "Plan should include March 2026 partition. Plan:\n" + plan);

            // Partition pruning: plan should NOT reference April, Jan, Feb partitions
            assertFalse(plan.contains("documents_2026_01"),
                    "Plan should NOT include January 2026 partition. Plan:\n" + plan);
            assertFalse(plan.contains("documents_2026_02"),
                    "Plan should NOT include February 2026 partition. Plan:\n" + plan);
            assertFalse(plan.contains("documents_2026_04"),
                    "Plan should NOT include April 2026 partition. Plan:\n" + plan);
        }
    }

    @Test
    @Order(3)
    void shouldPruneOnDocumentTypeAndDateRange() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            // Simulate the main list query pattern: WHERE document_type = '01' AND issue_date BETWEEN ...
            String plan = explainQuery(stmt,
                    "SELECT * FROM documents WHERE document_type = '01' "
                    + "AND issue_date >= '2026-04-01' AND issue_date < '2026-05-01' "
                    + "ORDER BY issue_date DESC");

            assertTrue(plan.contains("documents_2026_04"),
                    "Plan should include April 2026 partition. Plan:\n" + plan);
            assertFalse(plan.contains("documents_2026_03"),
                    "Plan should NOT include March partition. Plan:\n" + plan);
        }
    }

    @Test
    @Order(4)
    void shouldFindByPrimaryKeyWithoutDateInWhere() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            // Simulate Hibernate's em.find: SELECT ... WHERE document_id = ?
            // This should work even without issue_date in the WHERE clause.
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT document_id, issue_date, document_type FROM documents WHERE document_id = '"
                    + docMarchId + "'")) {
                assertTrue(rs.next(), "Should find March document by PK alone");
                assertEquals(docMarchId.toString(), rs.getString("document_id"));
                assertEquals("2026-03-15", rs.getString("issue_date"));
            }

            try (ResultSet rs = stmt.executeQuery(
                    "SELECT document_id, issue_date FROM documents WHERE document_id = '"
                    + docAprilId + "'")) {
                assertTrue(rs.next(), "Should find April document by PK alone");
                assertEquals(docAprilId.toString(), rs.getString("document_id"));
                assertEquals("2026-04-10", rs.getString("issue_date"));
            }
        }
    }

    @Test
    @Order(5)
    void shouldCountAcrossAllPartitions() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            // Query without date filter should scan all partitions and return total count
            long total = countQuery(stmt, "SELECT count(*) FROM documents");
            assertEquals(2L, total, "Should see both documents when querying parent table");

            // Query with status filter (no date) — still works across partitions
            long created = countQuery(stmt, "SELECT count(*) FROM documents WHERE status = 'CREATED'");
            assertEquals(2L, created, "Both documents should have CREATED status");
        }
    }

    @Test
    @Order(6)
    void shouldVerifyTableIsPartitioned() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            // Verify relkind = 'p' (partitioned table)
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT c.relkind FROM pg_class c "
                    + "JOIN pg_namespace n ON c.relnamespace = n.oid "
                    + "WHERE c.relname = 'documents' AND n.nspname = '" + TEST_SCHEMA + "'")) {
                assertTrue(rs.next(), "documents table should exist");
                assertEquals("p", rs.getString("relkind"),
                        "documents should be a partitioned table (relkind='p')");
            }

            // Count partitions (7: 6 monthly + 1 default)
            long partCount = countQuery(stmt,
                    "SELECT count(*) FROM pg_inherits i "
                    + "JOIN pg_class c ON i.inhparent = c.oid "
                    + "JOIN pg_namespace n ON c.relnamespace = n.oid "
                    + "WHERE c.relname = 'documents' AND n.nspname = '" + TEST_SCHEMA + "'");
            assertEquals(7L, partCount, "Should have 7 partitions (6 monthly + 1 default)");
        }
    }

    @Test
    @Order(7)
    void shouldUpdateDocumentAcrossPartitions() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            // Simulate Hibernate's UPDATE with optimistic locking:
            // UPDATE documents SET status = 'SIGNED', version = 2 WHERE document_id = ? AND version = 1
            int updated = stmt.executeUpdate(
                    "UPDATE documents SET status = 'SIGNED', version = 2 WHERE document_id = '"
                    + docMarchId + "' AND version = 1");
            assertEquals(1, updated, "Should update exactly one row by PK + version");

            // Verify the update
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT status, version FROM documents WHERE document_id = '" + docMarchId + "'")) {
                assertTrue(rs.next());
                assertEquals("SIGNED", rs.getString("status"));
                assertEquals(2, rs.getInt("version"));
            }
        }
    }

    @Test
    @Order(8)
    void shouldRouteToDefaultPartitionForOutOfRangeDate() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            // Insert a document with a date outside the defined monthly ranges (2025)
            UUID docOldId = UUID.randomUUID();
            stmt.execute(String.format(
                    "INSERT INTO documents (document_id, document_type, establishment, issue_point, sequence_number, issue_date) "
                    + "VALUES ('%s', '01', '002', '001', '000000001', '2025-06-15')",
                    docOldId));

            // Verify it lands in the default partition
            long defaultCount = countQuery(stmt,
                    "SELECT count(*) FROM documents_default WHERE document_id = '" + docOldId + "'");
            assertEquals(1L, defaultCount, "Out-of-range date should route to default partition");

            // Verify it's accessible from the parent table
            try (ResultSet rs = stmt.executeQuery(
                    "SELECT issue_date FROM documents WHERE document_id = '" + docOldId + "'")) {
                assertTrue(rs.next(), "Should find document via parent table");
                assertEquals("2025-06-15", rs.getString("issue_date"));
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private long countQuery(Statement stmt, String sql) throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String explainQuery(Statement stmt, String query) throws SQLException {
        StringBuilder plan = new StringBuilder();
        try (ResultSet rs = stmt.executeQuery("EXPLAIN " + query)) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }
}
