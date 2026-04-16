package auracore.key49.notify.email;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * Tests unitarios de los parsers y helpers estáticos de PlunkClient.
 * No requieren red — cubren la lógica de extracción JSON y sanitización.
 */
class PlunkClientTest {

    // ── parseVerifyResponse ──────────────────────────────────────────────────

    @Test
    void parseVerify_validEmail() {
        var json = """
                {"valid":true,"hasMxRecords":true,"domainExists":true,\
                "isDisposable":false,"isTypo":false,"reasons":[]}""";

        var result = PlunkClient.parseVerifyResponse(json);

        assertTrue(result.valid());
        assertTrue(result.hasMxRecords());
        assertTrue(result.domainExists());
        assertFalse(result.isDisposable());
        assertFalse(result.isTypo());
        assertTrue(result.reasons().isEmpty());
    }

    @Test
    void parseVerify_invalidEmail_noMx() {
        var json = """
                {"valid":false,"hasMxRecords":false,"domainExists":true,\
                "isDisposable":false,"isTypo":false,\
                "reasons":["no_mx_records","invalid_domain"]}""";

        var result = PlunkClient.parseVerifyResponse(json);

        assertFalse(result.valid());
        assertFalse(result.hasMxRecords());
        assertTrue(result.domainExists());
        assertEquals(List.of("no_mx_records", "invalid_domain"), result.reasons());
    }

    @Test
    void parseVerify_disposableEmail() {
        var json = """
                {"valid":true,"hasMxRecords":true,"domainExists":true,\
                "isDisposable":true,"isTypo":false,"reasons":[]}""";

        var result = PlunkClient.parseVerifyResponse(json);

        assertTrue(result.valid());
        assertTrue(result.isDisposable());
    }

    @Test
    void parseVerify_typoEmail() {
        var json = """
                {"valid":true,"hasMxRecords":true,"domainExists":true,\
                "isDisposable":false,"isTypo":true,"reasons":[]}""";

        var result = PlunkClient.parseVerifyResponse(json);

        assertTrue(result.valid());
        assertTrue(result.isTypo());
    }

    @Test
    void parseVerify_missingFields_usesDefaults() {
        // Minimal JSON — missing several optional fields
        var json = "{}";

        var result = PlunkClient.parseVerifyResponse(json);

        // Defaults: all true (safe — allow send when Plunk omits field)
        assertTrue(result.valid());
        assertTrue(result.hasMxRecords());
        assertTrue(result.domainExists());
        assertFalse(result.isDisposable());
        assertFalse(result.isTypo());
        assertTrue(result.reasons().isEmpty());
    }

    @Test
    void parseVerify_unavailable_isPermissive() {
        var result = PlunkClient.VerifyResult.unavailable();

        assertTrue(result.valid());
        assertTrue(result.hasMxRecords());
        assertTrue(result.domainExists());
        assertFalse(result.isDisposable());
        assertFalse(result.isTypo());
        assertTrue(result.reasons().isEmpty());
    }

    // ── escapeJson ───────────────────────────────────────────────────────────

    @Test
    void escapeJson_null_returnsEmpty() {
        assertEquals("", PlunkClient.escapeJson(null));
    }

    @Test
    void escapeJson_noSpecialChars() {
        assertEquals("hello world", PlunkClient.escapeJson("hello world"));
    }

    @Test
    void escapeJson_doubleQuotes() {
        assertEquals("say \\\"hello\\\"", PlunkClient.escapeJson("say \"hello\""));
    }

    @Test
    void escapeJson_backslash() {
        assertEquals("C:\\\\path", PlunkClient.escapeJson("C:\\path"));
    }

    @Test
    void escapeJson_newlines() {
        assertEquals("line1\\nline2\\r\\n", PlunkClient.escapeJson("line1\nline2\r\n"));
    }

    @Test
    void escapeJson_tab() {
        assertEquals("col1\\tcol2", PlunkClient.escapeJson("col1\tcol2"));
    }

    @Test
    void escapeJson_allSpecialChars() {
        var input    = "a\"b\\c\nd\re\tf";
        var expected = "a\\\"b\\\\c\\nd\\re\\tf";
        assertEquals(expected, PlunkClient.escapeJson(input));
    }

    // ── Attachment.of ────────────────────────────────────────────────────────

    @Test
    void attachment_of_encodesBase64() {
        byte[] content = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var att = PlunkClient.Attachment.of("test.txt", content, "text/plain");

        assertEquals("test.txt", att.filename());
        assertEquals("text/plain", att.type());
        // "hello" in Base64 is "aGVsbG8="
        assertEquals("aGVsbG8=", att.content());
    }
}
