package auracore.key49.ride.generator;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Generador de códigos QR para el RIDE.
 * Codifica la clave de acceso de 49 dígitos en una imagen PNG.
 */
public final class QrCodeGenerator {

    private static final int DEFAULT_SIZE = 150;

    private QrCodeGenerator() {}

    /**
     * Genera un código QR como bytes PNG a partir de la clave de acceso.
     *
     * @param accessKey clave de acceso de 49 dígitos
     * @return bytes PNG de la imagen QR
     */
    public static byte[] generate(String accessKey) {
        return generate(accessKey, DEFAULT_SIZE);
    }

    /**
     * Genera un código QR como bytes PNG con tamaño personalizado.
     *
     * @param accessKey clave de acceso de 49 dígitos
     * @param size ancho y alto en píxeles
     * @return bytes PNG de la imagen QR
     */
    public static byte[] generate(String accessKey, int size) {
        try {
            var writer = new QRCodeWriter();
            var hints = Map.of(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = writer.encode(accessKey, BarcodeFormat.QR_CODE, size, size, hints);

            var outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", outputStream);
            return outputStream.toByteArray();
        } catch (WriterException | IOException e) {
            throw new RideGenerationException("Failed to generate QR code for access key: " + accessKey, e);
        }
    }
}
