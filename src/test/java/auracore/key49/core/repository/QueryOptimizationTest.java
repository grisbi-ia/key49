package auracore.key49.core.repository;

import java.sql.ResultSet;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Test de integración que verifica el rendimiento de queries y la correcta
 * utilización de índices tras aplicar V006 (índice parcial para documentos en
 * tránsito + índice compuesto para queries de listado).
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QueryOptimizationTest {

    private static final String TEST_SCHEMA = "test_query_opt";

    @Inject
    javax.sql.DataSource dataSource;

    @BeforeAll
    void setup() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
            stmt.execute("CREATE SCHEMA " + TEST_SCHEMA);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            // Create partitioned documents table with all relevant columns
            stmt.execute("""
                    CREATE TABLE documents (
                        document_id         UUID NOT NULL DEFAULT gen_random_uuid(),
                        document_type       VARCHAR(2) NOT NULL,
                        establishment       VARCHAR(3) NOT NULL,
                        issue_point         VARCHAR(3) NOT NULL,
                        sequence_number     VARCHAR(9) NOT NULL,
                        access_key          VARCHAR(49),
                        idempotency_key     VARCHAR(50),
                        recipient_id        VARCHAR(20) NOT NULL DEFAULT '0000000000001',
                        recipient_id_type   VARCHAR(2) NOT NULL DEFAULT '04',
                        recipient_name      VARCHAR(300) NOT NULL DEFAULT 'Test',
                        request_origin      VARCHAR(10) NOT NULL DEFAULT 'JSON',
                        issue_date          DATE NOT NULL,
                        status              VARCHAR(20) NOT NULL DEFAULT 'CREATED',
                        request_payload     JSONB DEFAULT '{}',
                        next_retry_at       TIMESTAMP WITH TIME ZONE,
                        version             INT NOT NULL DEFAULT 1,
                        created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                        updated_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
                        PRIMARY KEY (document_id, issue_date)
                    ) PARTITION BY RANGE (issue_date)
                    """);

            // Monthly partitions
            stmt.execute(
                    "CREATE TABLE documents_2026_03 PARTITION OF documents FOR VALUES FROM ('2026-03-01') TO ('2026-04-01')");
            stmt.execute(
                    "CREATE TABLE documents_2026_04 PARTITION OF documents FOR VALUES FROM ('2026-04-01') TO ('2026-05-01')");
            stmt.execute("CREATE TABLE documents_default PARTITION OF documents DEFAULT");

            // Original V005 indexes
            stmt.execute("CREATE INDEX idx_documents_issue_date ON documents(issue_date DESC)");
            stmt.execute("CREATE INDEX idx_documents_recipient ON documents(recipient_id)");
            stmt.execute(
                    "CREATE INDEX idx_documents_retry ON documents(status, next_retry_at) WHERE status = 'RETRY'");
            stmt.execute(
                    "CREATE INDEX idx_documents_access_key ON documents(access_key) WHERE access_key IS NOT NULL");
            stmt.execute("CREATE INDEX idx_documents_type_date ON documents(document_type, issue_date DESC)");
            stmt.execute("CREATE INDEX idx_documents_created_at ON documents(created_at DESC)");

            // V006 indexes (the ones we're testing)
            stmt.execute("""
                    CREATE INDEX idx_documents_pending ON documents (status, next_retry_at)
                        WHERE status IN ('CREATED', 'SIGNED', 'SENT', 'RECEIVED', 'RETRY')
                    """);
            stmt.execute(
                    "CREATE INDEX idx_documents_status_type_date ON documents (status, document_type, issue_date DESC)");

            // Insert test data: mix of terminal and in-flight statuses
            String[] statuses = {"AUTHORIZED", "AUTHORIZED", "AUTHORIZED", "NOTIFIED", "NOTIFIED",
                "CREATED", "SIGNED", "SENT", "RECEIVED", "RETRY"};
            for (int i = 0; i < 100; i++) {
                String status = statuses[i % statuses.length];
                int day = (i % 28) + 1;
                String month = (i < 50) ? "04" : "03";
                String nextRetry = status.equals("RETRY") ? ", now() + INTERVAL '5 minutes'" : ", NULL";
                stmt.execute(String.format(
                        "INSERT INTO documents (document_type, establishment, issue_point, sequence_number, "
                        + "access_key, idempotency_key, issue_date, status, next_retry_at) "
                        + "VALUES ('01', '001', '001', '%09d', '%s', '%s', '2026-%s-%02d', '%s'%s)",
                        i + 1, UUID.randomUUID().toString().substring(0, 49 - 13) + "1234567890123",
                        UUID.randomUUID().toString(), month, day, status, nextRetry));
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
    void shouldHavePartialIndexForPendingDocuments() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);

            // Verify the partial index exists on the parent partitioned table
            // (PostgreSQL propagates it to each partition automatically)
            ResultSet rs = stmt.executeQuery("""
                    SELECT i.relname AS index_name,
                           pg_get_expr(ix.indpred, ix.indrelid) AS predicate
                    FROM pg_index ix
                    JOIN pg_class i ON ix.indexrelid = i.oid
                    JOIN pg_class t ON ix.indrelid = t.oid
                    JOIN pg_namespace n ON t.relnamespace = n.oid
                    WHERE n.nspname = 'test_query_opt'
                      AND t.relname = 'documents'
                      AND ix.indpred IS NOT NULL
                      AND pg_get_expr(ix.indpred, ix.indrelid) LIKE '%CREATED%'
                    """);

            assertTrue(rs.next(), "Should find a partial index with CREATED in its predicate");
            String predicate = rs.getString("predicate");
            assertTrue(predicate.contains("CREATED") && predicate.contains("SIGNED")
                    && predicate.contains("SENT") && predicate.contains("RECEIVED")
                    && predicate.contains("RETRY"),
                    "Partial index predicate should cover all in-flight statuses.\nPredicate: " + predicate);
        }
    }

    @Test
    @Order(2)
    void shouldUsePartialIndexForRetryReady() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            ResultSet rs = stmt.executeQuery("""
                    EXPLAIN SELECT * FROM documents
                    WHERE status = 'RETRY' AND next_retry_at <= now()
                    """);

            StringBuilder plan = new StringBuilder();
            while (rs.next()) {
                plan.append(rs.getString(1)).append("\n");
            }
            String planStr = plan.toString();

            // Should use an index scan with status/retry filter
            assertTrue(planStr.contains("Index Scan") || planStr.contains("Bitmap"),
                    "Query plan should use an Index or Bitmap scan for RETRY.\nPlan:\n" + planStr);
            assertTrue(planStr.contains("RETRY") || planStr.contains("status"),
                    "Query plan should reference RETRY status.\nPlan:\n" + planStr);
        }
    }

    @Test
    @Order(3)
    void shouldUseCompositeIndexForListQueries() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            ResultSet rs = stmt.executeQuery("""
                    EXPLAIN SELECT * FROM documents
                    WHERE status = 'AUTHORIZED'
                      AND document_type = '01'
                      AND issue_date >= '2026-04-01' AND issue_date < '2026-05-01'
                    ORDER BY issue_date DESC
                    LIMIT 20
                    """);

            StringBuilder plan = new StringBuilder();
            while (rs.next()) {
                plan.append(rs.getString(1)).append("\n");
            }
            String planStr = plan.toString();

            // The composite index should be used (partition indexes have auto-generated names
            // like documents_2026_04_status_document_type_issue_date_idx)
            assertTrue(planStr.contains("Index Scan") || planStr.contains("Bitmap"),
                    "Query plan should use an Index or Bitmap scan.\nPlan:\n" + planStr);
            assertTrue(planStr.contains("status") && planStr.contains("document_type"),
                    "Query plan should reference status and document_type.\nPlan:\n" + planStr);
        }
    }

    @Test
    @Order(4)
    void shouldUseDatePartitionPruning() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            ResultSet rs = stmt.executeQuery("""
                    EXPLAIN SELECT * FROM documents
                    WHERE document_type = '01'
                      AND issue_date >= '2026-04-01' AND issue_date < '2026-05-01'
                    ORDER BY issue_date DESC
                    """);

            StringBuilder plan = new StringBuilder();
            while (rs.next()) {
                plan.append(rs.getString(1)).append("\n");
            }
            String planStr = plan.toString();

            // Should access documents_2026_04 only, not documents_2026_03
            assertTrue(planStr.contains("documents_2026_04"),
                    "Plan should reference the April partition.\nPlan:\n" + planStr);
            assertTrue(!planStr.contains("documents_2026_03"),
                    "Plan should NOT scan the March partition.\nPlan:\n" + planStr);
        }
    }

    @Test
    @Order(5)
    void shouldUseAccessKeyIndex() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            ResultSet rs = stmt.executeQuery("""
                    EXPLAIN SELECT * FROM documents
                    WHERE access_key = 'nonexistent_key_for_testing_purpose_00000000000'
                    """);

            StringBuilder plan = new StringBuilder();
            while (rs.next()) {
                plan.append(rs.getString(1)).append("\n");
            }
            String planStr = plan.toString();

            // Partition indexes have auto-generated names like documents_2026_04_access_key_idx
            assertTrue(planStr.contains("Index Scan") || planStr.contains("Bitmap"),
                    "Query should use an Index or Bitmap scan for access_key.\nPlan:\n" + planStr);
            assertTrue(planStr.contains("access_key"),
                    "Query plan should reference access_key.\nPlan:\n" + planStr);
        }
    }

    @Test
    @Order(6)
    void shouldCountPendingDocumentsEfficiently() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            // In-flight statuses: CREATED, SIGNED, SENT, RECEIVED, RETRY
            // We inserted 100 rows: 50% terminal (AUTHORIZED/NOTIFIED), 50% in-flight
            ResultSet rs = stmt.executeQuery("""
                    SELECT count(*) FROM documents
                    WHERE status IN ('CREATED', 'SIGNED', 'SENT', 'RECEIVED', 'RETRY')
                    """);
            assertTrue(rs.next());
            long pending = rs.getLong(1);
            assertEquals(50, pending, "Half of 100 test docs should be in-flight");

            // Terminal statuses should be the other half
            rs = stmt.executeQuery("""
                    SELECT count(*) FROM documents
                    WHERE status IN ('AUTHORIZED', 'NOTIFIED')
                    """);
            assertTrue(rs.next());
            long terminal = rs.getLong(1);
            assertEquals(50, terminal, "Other half should be terminal");
        }
    }

    @Test
    @Order(7)
    void shouldVerifyAllIndexesExist() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);

            // Count all indexes in our test schema
            ResultSet rs = stmt.executeQuery("""
                    SELECT i.relname AS index_name
                    FROM pg_index ix
                    JOIN pg_class i ON ix.indexrelid = i.oid
                    JOIN pg_class t ON ix.indrelid = t.oid
                    JOIN pg_namespace n ON t.relnamespace = n.oid
                    WHERE n.nspname = 'test_query_opt'
                      AND t.relname LIKE 'documents%'
                    ORDER BY i.relname
                    """);

            int count = 0;
            boolean hasPending = false;
            boolean hasStatusTypeDate = false;
            while (rs.next()) {
                String name = rs.getString("index_name");
                if (name.contains("idx_documents_pending")) {
                    hasPending = true;
                }
                if (name.contains("idx_documents_status_type_date")) {
                    hasStatusTypeDate = true;
                }
                count++;
            }

            assertTrue(hasPending, "idx_documents_pending should exist");
            assertTrue(hasStatusTypeDate, "idx_documents_status_type_date should exist");
            // PK (per partition) + 7 original indexes (per partition) + 2 V006 indexes = many
            assertTrue(count >= 10, "Should have at least 10 indexes across partitions, got: " + count);
        }
    }

    @Test
    @Order(8)
    void shouldUseRecipientIdIndex() throws Exception {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            conn.setAutoCommit(true);
            stmt.execute("SET search_path TO " + TEST_SCHEMA);

            ResultSet rs = stmt.executeQuery("""
                    EXPLAIN SELECT * FROM documents
                    WHERE recipient_id = '0000000000001'
                    ORDER BY issue_date DESC
                    LIMIT 20
                    """);

            StringBuilder plan = new StringBuilder();
            while (rs.next()) {
                plan.append(rs.getString(1)).append("\n");
            }
            String planStr = plan.toString();

            // Partition indexes have auto-generated names like documents_2026_04_recipient_id_idx
            assertTrue(planStr.contains("Index Scan") || planStr.contains("Bitmap"),
                    "Query should use an Index or Bitmap scan for recipient_id.\nPlan:\n" + planStr);
            assertTrue(planStr.contains("recipient_id"),
                    "Query plan should reference recipient_id.\nPlan:\n" + planStr);
        }
    }
}
