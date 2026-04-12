package auracore.key49.core.tenant;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Tests de integración para la función PL/pgSQL clone_schema() y el esquema
 * tenant_template. Ejecuta la migración V006, clona el template y verifica que
 * la estructura resultante es idéntica.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("clone_schema() — PL/pgSQL function")
class CloneSchemaFunctionTest {

    private static final String CLONE_TARGET = "tenant_clone_test";

    @Inject
    DataSource dataSource;

    @BeforeAll
    void setupMigration() throws Exception {
        String sql = Files.readString(
                Path.of("db/migrations/public/V006__create_clone_schema_and_template.sql"));
        try (var conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (var stmt = conn.createStatement()) {
                stmt.execute(sql);
            }
        }
    }

    @AfterEach
    void cleanupTarget() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS " + CLONE_TARGET + " CASCADE");
        }
    }

    @AfterAll
    void cleanup() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS " + CLONE_TARGET + " CASCADE");
            stmt.execute("DROP SCHEMA IF EXISTS tenant_template CASCADE");
            stmt.execute("DROP FUNCTION IF EXISTS public.clone_schema(TEXT, TEXT)");
        }
    }

    // ── Happy path ──────────────────────────────────────────────────────────
    @Test
    @DisplayName("Clona tenant_template con todas las tablas")
    void shouldCloneAllTables() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("SELECT clone_schema('tenant_template', '" + CLONE_TARGET + "')");

            var tables = queryColumn(stmt, """
                    SELECT c.relname
                    FROM pg_class c
                    JOIN pg_namespace n ON c.relnamespace = n.oid
                    WHERE n.nspname = '%s'
                      AND c.relkind IN ('r', 'p')
                      AND NOT c.relispartition
                    ORDER BY c.relname""".formatted(CLONE_TARGET));

            assertEquals(
                    List.of("audit_log", "documents", "outbox", "webhook_deliveries"),
                    tables,
                    "Should have exactly the 4 tenant tables");
        }
    }

    @Test
    @DisplayName("documents es tabla particionada")
    void shouldCloneDocumentsAsPartitioned() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("SELECT clone_schema('tenant_template', '" + CLONE_TARGET + "')");

            var rs = stmt.executeQuery("""
                    SELECT c.relkind
                    FROM pg_class c
                    JOIN pg_namespace n ON c.relnamespace = n.oid
                    WHERE n.nspname = '%s' AND c.relname = 'documents'
                    """.formatted(CLONE_TARGET));
            assertTrue(rs.next());
            assertEquals("p", rs.getString(1), "documents should be partitioned (relkind='p')");
        }
    }

    @Test
    @DisplayName("Particiones mensuales + default se clonan correctamente")
    void shouldCloneAllPartitions() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("SELECT clone_schema('tenant_template', '" + CLONE_TARGET + "')");

            int sourceCount = countQuery(stmt, """
                    SELECT COUNT(*)
                    FROM pg_inherits i
                    JOIN pg_class parent ON i.inhparent = parent.oid
                    JOIN pg_namespace n ON parent.relnamespace = n.oid
                    WHERE n.nspname = 'tenant_template' AND parent.relname = 'documents'""");

            int targetCount = countQuery(stmt, """
                    SELECT COUNT(*)
                    FROM pg_inherits i
                    JOIN pg_class parent ON i.inhparent = parent.oid
                    JOIN pg_namespace n ON parent.relnamespace = n.oid
                    WHERE n.nspname = '%s' AND parent.relname = 'documents'""".formatted(CLONE_TARGET));

            assertEquals(sourceCount, targetCount, "Partition count should match");
            assertTrue(sourceCount >= 13, "Should have at least 13 partitions (12 months + default)");
        }
    }

    @Test
    @DisplayName("Columnas de documents son idénticas entre source y target")
    void shouldCloneIdenticalColumns() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("SELECT clone_schema('tenant_template', '" + CLONE_TARGET + "')");

            for (String table : List.of("documents", "outbox", "webhook_deliveries", "audit_log")) {
                var sourceCols = queryColumn(stmt, colQuery("tenant_template", table));
                var targetCols = queryColumn(stmt, colQuery(CLONE_TARGET, table));
                assertEquals(sourceCols, targetCols,
                        "Column definitions for " + table + " should match exactly");
            }
        }
    }

    @Test
    @DisplayName("Índices de documents se clonan correctamente")
    void shouldCloneDocumentsIndexes() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("SELECT clone_schema('tenant_template', '" + CLONE_TARGET + "')");

            int sourceIndexCount = countQuery(stmt, """
                    SELECT COUNT(*)
                    FROM pg_indexes
                    WHERE schemaname = 'tenant_template' AND tablename = 'documents'""");

            int targetIndexCount = countQuery(stmt, """
                    SELECT COUNT(*)
                    FROM pg_indexes
                    WHERE schemaname = '%s' AND tablename = 'documents'""".formatted(CLONE_TARGET));

            assertEquals(sourceIndexCount, targetIndexCount,
                    "documents index count should match");
            assertTrue(sourceIndexCount >= 9, "Should have at least 9 indexes on documents");
        }
    }

    @Test
    @DisplayName("PRIMARY KEY existe en documents clonado")
    void shouldClonePrimaryKey() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("SELECT clone_schema('tenant_template', '" + CLONE_TARGET + "')");

            int pkCount = countQuery(stmt, """
                    SELECT COUNT(*)
                    FROM pg_constraint con
                    JOIN pg_class c ON con.conrelid = c.oid
                    JOIN pg_namespace n ON c.relnamespace = n.oid
                    WHERE n.nspname = '%s'
                      AND c.relname = 'documents'
                      AND con.contype = 'p'""".formatted(CLONE_TARGET));

            assertEquals(1, pkCount, "documents should have a primary key");
        }
    }

    @Test
    @DisplayName("UNIQUE constraints se clonan en documents")
    void shouldCloneUniqueConstraints() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("SELECT clone_schema('tenant_template', '" + CLONE_TARGET + "')");

            int sourceUq = countQuery(stmt, """
                    SELECT COUNT(*)
                    FROM pg_constraint con
                    JOIN pg_class c ON con.conrelid = c.oid
                    JOIN pg_namespace n ON c.relnamespace = n.oid
                    WHERE n.nspname = 'tenant_template'
                      AND c.relname = 'documents'
                      AND con.contype = 'u'""");

            int targetUq = countQuery(stmt, """
                    SELECT COUNT(*)
                    FROM pg_constraint con
                    JOIN pg_class c ON con.conrelid = c.oid
                    JOIN pg_namespace n ON c.relnamespace = n.oid
                    WHERE n.nspname = '%s'
                      AND c.relname = 'documents'
                      AND con.contype = 'u'""".formatted(CLONE_TARGET));

            assertEquals(sourceUq, targetUq, "UNIQUE constraint count should match");
            assertEquals(3, sourceUq, "documents should have 3 UNIQUE constraints");
        }
    }

    @Test
    @DisplayName("CHECK constraints se clonan correctamente")
    void shouldCloneCheckConstraints() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("SELECT clone_schema('tenant_template', '" + CLONE_TARGET + "')");

            for (String table : List.of("documents", "webhook_deliveries")) {
                int sourceChk = countQuery(stmt, chkQuery("tenant_template", table));
                int targetChk = countQuery(stmt, chkQuery(CLONE_TARGET, table));
                assertEquals(sourceChk, targetChk,
                        "CHECK constraint count for " + table + " should match");
            }
        }
    }

    @Test
    @DisplayName("Índices de tablas regulares se clonan")
    void shouldCloneRegularTableIndexes() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("SELECT clone_schema('tenant_template', '" + CLONE_TARGET + "')");

            for (String table : List.of("outbox", "webhook_deliveries", "audit_log")) {
                int sourceIdx = countQuery(stmt,
                        "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'tenant_template' AND tablename = '%s'"
                                .formatted(table));
                int targetIdx = countQuery(stmt,
                        "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = '%s' AND tablename = '%s'"
                                .formatted(CLONE_TARGET, table));
                assertEquals(sourceIdx, targetIdx,
                        "Index count for " + table + " should match");
            }
        }
    }

    // ── Error cases ─────────────────────────────────────────────────────────
    @Test
    @DisplayName("Rechaza clonar a esquema que ya existe")
    void shouldRejectDuplicateTarget() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("SELECT clone_schema('tenant_template', '" + CLONE_TARGET + "')");

            var ex = assertThrows(SQLException.class, ()
                    -> stmt.execute("SELECT clone_schema('tenant_template', '" + CLONE_TARGET + "')"));
            assertTrue(ex.getMessage().contains("already exists"),
                    "Should mention schema already exists");
        }
    }

    @Test
    @DisplayName("Rechaza esquema fuente inexistente")
    void shouldRejectNonExistentSource() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            var ex = assertThrows(SQLException.class, ()
                    -> stmt.execute("SELECT clone_schema('schema_que_no_existe', '" + CLONE_TARGET + "')"));
            assertTrue(ex.getMessage().contains("does not exist"),
                    "Should mention source does not exist");
        }
    }

    @Test
    @DisplayName("Rechaza nombre de esquema inválido (mayúsculas)")
    void shouldRejectUpperCaseSchemaName() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            var ex = assertThrows(SQLException.class, ()
                    -> stmt.execute("SELECT clone_schema('tenant_template', 'Invalid_Name')"));
            assertTrue(ex.getMessage().contains("Invalid schema name"),
                    "Should mention invalid schema name");
        }
    }

    @Test
    @DisplayName("Rechaza nombre de esquema inválido (guiones)")
    void shouldRejectDashesInSchemaName() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            var ex = assertThrows(SQLException.class, ()
                    -> stmt.execute("SELECT clone_schema('tenant_template', 'tenant-with-dashes')"));
            assertTrue(ex.getMessage().contains("Invalid schema name"),
                    "Should mention invalid schema name");
        }
    }

    @Test
    @DisplayName("Rechaza nombre de esquema demasiado largo")
    void shouldRejectTooLongSchemaName() throws SQLException {
        String longName = "a".repeat(64);
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            var ex = assertThrows(SQLException.class, ()
                    -> stmt.execute("SELECT clone_schema('tenant_template', '" + longName + "')"));
            assertTrue(ex.getMessage().contains("Invalid schema name"),
                    "Should mention invalid schema name");
        }
    }

    @Test
    @DisplayName("Rechaza parámetros NULL")
    void shouldRejectNullParameters() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            var ex = assertThrows(SQLException.class, ()
                    -> stmt.execute("SELECT clone_schema(NULL, 'some_target')"));
            assertTrue(ex.getMessage().contains("NULL"),
                    "Should mention NULL parameter");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────
    private String colQuery(String schema, String table) {
        return """
                SELECT column_name || '|' || data_type || '|' || COALESCE(character_maximum_length::text, '') || '|' || is_nullable
                FROM information_schema.columns
                WHERE table_schema = '%s' AND table_name = '%s'
                ORDER BY ordinal_position""".formatted(schema, table);
    }

    private String chkQuery(String schema, String table) {
        return """
                SELECT COUNT(*)
                FROM pg_constraint con
                JOIN pg_class c ON con.conrelid = c.oid
                JOIN pg_namespace n ON c.relnamespace = n.oid
                WHERE n.nspname = '%s'
                  AND c.relname = '%s'
                  AND con.contype = 'c'""".formatted(schema, table);
    }

    private List<String> queryColumn(java.sql.Statement stmt, String sql) throws SQLException {
        var list = new ArrayList<String>();
        try (var rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(rs.getString(1));
            }
        }
        return list;
    }

    private int countQuery(java.sql.Statement stmt, String sql) throws SQLException {
        try (var rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
