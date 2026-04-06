package auracore.key49.ride.generator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests para QrCodeGenerator.
 */
class QrCodeGeneratorTest {

    private static final String ACCESS_KEY = "2506202501099271531200110010020000000011234567813";

    @Test
    void generateReturnsValidPngBytes() {
        byte[] qrBytes = QrCodeGenerator.generate(ACCESS_KEY);

        assertNotNull(qrBytes);
        assertTrue(qrBytes.length > 0);
        // PNG magic bytes: 0x89 0x50 0x4E 0x47
        assertTrue(qrBytes[0] == (byte) 0x89);
        assertTrue(qrBytes[1] == (byte) 0x50); // 'P'
        assertTrue(qrBytes[2] == (byte) 0x4E); // 'N'
        assertTrue(qrBytes[3] == (byte) 0x47); // 'G'
    }

    @Test
    void generateWithCustomSizeReturnsLargerImage() {
        byte[] smallQr = QrCodeGenerator.generate(ACCESS_KEY, 50);
        byte[] largeQr = QrCodeGenerator.generate(ACCESS_KEY, 300);

        assertNotNull(smallQr);
        assertNotNull(largeQr);
        assertTrue(largeQr.length > smallQr.length,
                "Larger QR should produce more bytes");
    }

    @Test
    void generateWithShortTextWorks() {
        byte[] qrBytes = QrCodeGenerator.generate("12345");

        assertNotNull(qrBytes);
        assertTrue(qrBytes.length > 0);
    }

    @Test
    void generateWithVeryLongTextWorks() {
        String longText = "A".repeat(500);
        byte[] qrBytes = QrCodeGenerator.generate(longText);

        assertNotNull(qrBytes);
        assertTrue(qrBytes.length > 0);
    }

    @Test
    void generateDefaultSizeConsistency() {
        byte[] first = QrCodeGenerator.generate(ACCESS_KEY);
        byte[] second = QrCodeGenerator.generate(ACCESS_KEY);

        // Same input should produce same output
        assertNotNull(first);
        assertNotNull(second);
        assertTrue(first.length == second.length, "Same input should produce same size QR");
    }
}
