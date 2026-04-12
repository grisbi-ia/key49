package auracore.key49.core.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import auracore.key49.core.service.TenantAdminService.CreateTenantData;
import auracore.key49.core.service.TenantAdminService.TenantException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Tests de integración para el provisioning automático de tenants en
 * TenantAdminService. Verifica que crear un tenant ejecuta clone_schema(), crea
 * el esquema con tablas accesibles y transiciona a status='active'.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("TenantAdminService — provisioning automático")
class TenantAdminServiceIntegrationTest {

    private static final String TEST_SCHEMA = "tenant_integ_test";
    private static final String TEST_SCHEMA_DUP = "tenant_integ_dup";
    private static final String TEST_RUC = "1790016919001";
    private static final String TEST_RUC_2 = "0992339411001";

    @Inject
    TenantAdminService tenantAdminService;

    @Inject
    DataSource dataSource;

    private final List<String> createdSchemas = new java.util.ArrayList<>();
    private final List<UUID> createdTenantIds = new java.util.ArrayList<>();

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
    void cleanupSchemasAndTenants() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            for (String schema : createdSchemas) {
                stmt.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
            for (UUID id : createdTenantIds) {
                stmt.execute("DELETE FROM public.tenants WHERE tenant_id = '" + id + "'");
            }
        }
        createdSchemas.clear();
        createdTenantIds.clear();
    }

    @AfterAll
    void cleanup() throws SQLException {
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE");
            stmt.execute("DROP SCHEMA IF EXISTS " + TEST_SCHEMA_DUP + " CASCADE");
            stmt.execute("DROP SCHEMA IF EXISTS tenant_template CASCADE");
            stmt.execute("DROP FUNCTION IF EXISTS public.clone_schema(TEXT, TEXT)");
        }
    }

    // ── Happy path ──────────────────────────────────────────────────────────
    @Test
    @DisplayName("Crear tenant provisiona esquema y transiciona a 'active'")
    void createTenantProvisionesSchemaAndActivates() {
        var data = new CreateTenantData(
                TEST_RUC, "EMPRESA INTEGRACIÓN S.A.", "EMPRESA INT",
                "Av. Amazonas N24-100, Quito", false, null, false, null,
                "test", TEST_SCHEMA);

        var tenant = tenantAdminService.create(data);
        createdSchemas.add(TEST_SCHEMA);
        createdTenantIds.add(tenant.id);

        assertNotNull(tenant.id, "Tenant ID should be generated");
        assertEquals("active", tenant.status, "Status should be 'active' after provisioning");
        assertEquals(TEST_SCHEMA, tenant.schemaName);
    }

    @Test
    @DisplayName("Esquema clonado contiene las 4 tablas de tenant")
    void clonedSchemaContainsAllTables() throws SQLException {
        var data = new CreateTenantData(
                TEST_RUC, "EMPRESA TABLAS S.A.", "EMPRESA TAB",
                "Av. 6 de Diciembre N30-100, Quito", false, null, false, null,
                "test", TEST_SCHEMA);

        var tenant = tenantAdminService.create(data);
        createdSchemas.add(TEST_SCHEMA);
        createdTenantIds.add(tenant.id);

        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            var rs = stmt.executeQuery("""
                    SELECT c.relname
                    FROM pg_class c
                    JOIN pg_namespace n ON c.relnamespace = n.oid
                    WHERE n.nspname = '%s'
                      AND c.relkind IN ('r', 'p')
                      AND NOT c.relispartition
                    ORDER BY c.relname""".formatted(TEST_SCHEMA));

            var tables = new java.util.ArrayList<String>();
            while (rs.next()) {
                tables.add(rs.getString(1));
            }

            assertEquals(
                    List.of("audit_log", "documents", "outbox", "webhook_deliveries"),
                    tables,
                    "Cloned schema should have the 4 tenant tables");
        }
    }

    @Test
    @DisplayName("Tablas del esquema clonado son accesibles con SET search_path")
    void clonedSchemaTablesAreAccessible() throws SQLException {
        var data = new CreateTenantData(
                TEST_RUC, "EMPRESA ACCESO S.A.", "EMPRESA ACC",
                "Calle Guayaquil N5-100, Quito", false, null, false, null,
                "test", TEST_SCHEMA);

        var tenant = tenantAdminService.create(data);
        createdSchemas.add(TEST_SCHEMA);
        createdTenantIds.add(tenant.id);

        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("SET search_path TO '" + TEST_SCHEMA + "', public");

            // Verify we can query each table without errors
            for (String table : List.of("documents", "outbox", "webhook_deliveries", "audit_log")) {
                var rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table);
                assertTrue(rs.next(), "Should be able to query " + table);
                assertEquals(0, rs.getInt(1), table + " should be empty");
            }
        }
    }

    // ── Duplicados ──────────────────────────────────────────────────────────
    @Test
    @DisplayName("Crear tenant con RUC duplicado falla con DUPLICATE_RUC")
    void duplicateRucFails() {
        var data1 = new CreateTenantData(
                TEST_RUC, "EMPRESA PRIMERA S.A.", null,
                "Quito", false, null, false, null,
                "test", TEST_SCHEMA);

        var tenant = tenantAdminService.create(data1);
        createdSchemas.add(TEST_SCHEMA);
        createdTenantIds.add(tenant.id);

        var data2 = new CreateTenantData(
                TEST_RUC, "EMPRESA SEGUNDA S.A.", null,
                "Guayaquil", false, null, false, null,
                "test", TEST_SCHEMA_DUP);

        var ex = assertThrows(TenantException.class, () -> tenantAdminService.create(data2));
        assertEquals("DUPLICATE_RUC", ex.code());
        assertEquals(409, ex.httpStatus());
    }

    @Test
    @DisplayName("Crear tenant con schema_name duplicado falla con DUPLICATE_SCHEMA")
    void duplicateSchemaNameFails() {
        var data1 = new CreateTenantData(
                TEST_RUC, "EMPRESA ALPHA S.A.", null,
                "Quito", false, null, false, null,
                "test", TEST_SCHEMA);

        var tenant = tenantAdminService.create(data1);
        createdSchemas.add(TEST_SCHEMA);
        createdTenantIds.add(tenant.id);

        var data2 = new CreateTenantData(
                TEST_RUC_2, "EMPRESA BETA S.A.", null,
                "Guayaquil", false, null, false, null,
                "test", TEST_SCHEMA);

        var ex = assertThrows(TenantException.class, () -> tenantAdminService.create(data2));
        assertEquals("DUPLICATE_SCHEMA", ex.code());
        assertEquals(409, ex.httpStatus());
    }

    // ── Provisioning failure ────────────────────────────────────────────────
    @Test
    @DisplayName("Si clone_schema falla por esquema ya existente en PG → PROVISIONING_FAILED")
    void provisioningFailsIfSchemaAlreadyExistsInPg() throws SQLException {
        // Pre-create the schema directly in PG to simulate conflict
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("CREATE SCHEMA " + TEST_SCHEMA);
        }
        createdSchemas.add(TEST_SCHEMA);

        var data = new CreateTenantData(
                TEST_RUC, "EMPRESA CONFLICTO S.A.", null,
                "Cuenca", false, null, false, null,
                "test", TEST_SCHEMA);

        var ex = assertThrows(TenantException.class, () -> {
            var t = tenantAdminService.create(data);
            createdTenantIds.add(t.id);
        });
        assertEquals("PROVISIONING_FAILED", ex.code());
        assertEquals(500, ex.httpStatus());
    }
}
