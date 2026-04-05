package auracore.key49.core.tenant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TenantSchemaResolverTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "tenant_abc123",
            "tenant_a1b2c3",
            "public",
            "tenant_000",
            "a_b_c"
    })
    void shouldAcceptValidSchemaNames(String schemaName) {
        assertDoesNotThrow(() -> TenantSchemaResolver.validate(schemaName));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "tenant-abc",          // hyphen not allowed
            "TENANT_ABC",          // uppercase not allowed
            "tenant abc",          // spaces not allowed
            "tenant;DROP TABLE",   // SQL injection
            "tenant'--",           // SQL injection
            "tenant\"abc",         // quotes
            "tenant.abc",          // dots
            "tenant/abc",          // slashes
            "../../../etc/passwd"  // path traversal
    })
    void shouldRejectInvalidSchemaNames(String schemaName) {
        assertThrows(IllegalArgumentException.class,
                () -> TenantSchemaResolver.validate(schemaName));
    }

    @Test
    void shouldRejectNullSchema() {
        assertThrows(IllegalArgumentException.class,
                () -> TenantSchemaResolver.validate(null));
    }

    @Test
    void shouldRejectBlankSchema() {
        assertThrows(IllegalArgumentException.class,
                () -> TenantSchemaResolver.validate(""));
        assertThrows(IllegalArgumentException.class,
                () -> TenantSchemaResolver.validate("   "));
    }

    @Test
    void shouldRejectSchemaExceedingMaxLength() {
        var longName = "a".repeat(64);
        assertThrows(IllegalArgumentException.class,
                () -> TenantSchemaResolver.validate(longName));
    }

    @Test
    void shouldAcceptSchemaAtMaxLength() {
        var maxName = "a".repeat(63);
        assertDoesNotThrow(() -> TenantSchemaResolver.validate(maxName));
    }

    @Test
    void shouldBuildCorrectSearchPathSql() {
        var sql = TenantSchemaResolver.buildSearchPathSql("tenant_abc123");
        assertEquals("SET search_path TO 'tenant_abc123', public", sql);
    }

    @Test
    void shouldBuildSearchPathSqlWithValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> TenantSchemaResolver.buildSearchPathSql("'; DROP TABLE tenants;--"));
    }
}
