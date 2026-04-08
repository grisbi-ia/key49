package auracore.key49.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class StoragePathTest {

    private static final String TENANT = "tenant_abc123";
    private static final String ACCESS_KEY = "2506202501099271531200110010020000000011234567813";
    private static final String DOC_TYPE = "01";

    @Test
    void shouldBuildPathWithCorrectStructure() {
        var date = LocalDate.of(2025, 6, 15);

        var path = StoragePath.build(TENANT, date, DOC_TYPE, ACCESS_KEY, DocumentArtifact.SIGNED_XML);

        assertEquals("tenant_abc123/2025/06/01/2506202501099271531200110010020000000011234567813/signed.xml", path);
    }

    @Test
    void shouldZeroPadSingleDigitMonth() {
        var date = LocalDate.of(2025, 1, 20);

        var path = StoragePath.build(TENANT, date, DOC_TYPE, ACCESS_KEY, DocumentArtifact.UNSIGNED_XML);

        assertTrue(path.contains("/01/"), "Single-digit month should be zero-padded");
        assertEquals("tenant_abc123/2025/01/01/2506202501099271531200110010020000000011234567813/unsigned.xml", path);
    }

    @Test
    void shouldNotZeroPadDoubleDigitMonth() {
        var date = LocalDate.of(2025, 12, 5);

        var path = StoragePath.build(TENANT, date, DOC_TYPE, ACCESS_KEY, DocumentArtifact.RIDE);

        assertTrue(path.contains("/12/"));
    }

    @ParameterizedTest
    @EnumSource(DocumentArtifact.class)
    void shouldEndWithCorrectFilenameForEachArtifact(DocumentArtifact artifact) {
        var date = LocalDate.of(2025, 6, 15);

        var path = StoragePath.build(TENANT, date, DOC_TYPE, ACCESS_KEY, artifact);

        assertTrue(path.endsWith("/" + artifact.filename()),
                "Path should end with artifact filename: " + artifact.filename());
    }

    @Test
    void shouldBuildPathForDifferentDocTypes() {
        var date = LocalDate.of(2025, 6, 15);

        var pathInvoice = StoragePath.build(TENANT, date, "01", ACCESS_KEY, DocumentArtifact.SIGNED_XML);
        var pathCreditNote = StoragePath.build(TENANT, date, "04", ACCESS_KEY, DocumentArtifact.SIGNED_XML);

        assertTrue(pathInvoice.contains("/01/"));
        assertTrue(pathCreditNote.contains("/04/"));
    }

    @Test
    void shouldBuildPrefixEndingWithSlash() {
        var date = LocalDate.of(2025, 6, 15);

        var prefix = StoragePath.prefix(TENANT, date, DOC_TYPE, ACCESS_KEY);

        assertTrue(prefix.endsWith("/"), "Prefix should end with /");
        assertEquals("tenant_abc123/2025/06/01/2506202501099271531200110010020000000011234567813/", prefix);
    }

    @Test
    void shouldBuildPrefixMatchingPathPrefix() {
        var date = LocalDate.of(2025, 6, 15);

        var prefix = StoragePath.prefix(TENANT, date, DOC_TYPE, ACCESS_KEY);
        var fullPath = StoragePath.build(TENANT, date, DOC_TYPE, ACCESS_KEY, DocumentArtifact.AUTHORIZED_XML);

        assertTrue(fullPath.startsWith(prefix),
                "Full path should start with directory prefix");
    }

    @Test
    void shouldHandleYearBoundary() {
        var date = LocalDate.of(2030, 1, 1);

        var path = StoragePath.build(TENANT, date, DOC_TYPE, ACCESS_KEY, DocumentArtifact.RIDE);

        assertTrue(path.startsWith("tenant_abc123/2030/01/"));
    }
}
