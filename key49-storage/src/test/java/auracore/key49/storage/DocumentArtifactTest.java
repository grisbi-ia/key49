package auracore.key49.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DocumentArtifactTest {

    @ParameterizedTest
    @CsvSource({
            "UNSIGNED_XML, unsigned.xml, application/xml",
            "SIGNED_XML,   signed.xml,   application/xml",
            "AUTHORIZED_XML, authorized.xml, application/xml",
            "RIDE,         ride.pdf,     application/pdf"
    })
    void shouldHaveCorrectFilenameAndContentType(DocumentArtifact artifact, String expectedFilename,
                                                  String expectedContentType) {
        assertEquals(expectedFilename, artifact.filename());
        assertEquals(expectedContentType, artifact.contentType());
    }

    @Test
    void shouldHaveExactlyFourValues() {
        assertEquals(4, DocumentArtifact.values().length);
    }

    @Test
    void shouldResolveFromName() {
        for (var artifact : DocumentArtifact.values()) {
            assertNotNull(DocumentArtifact.valueOf(artifact.name()));
        }
    }
}
