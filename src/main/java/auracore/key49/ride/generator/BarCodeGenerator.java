package auracore.key49.ride.generator;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Generador de códigos de barras Code 128 para el RIDE.
 * Codifica la clave de acceso de 49 dígitos en una imagen PNG 1D.
 *
 * <p>Code 128 modo C codifica pares de dígitos, resultando en un barcode
 * compacto ideal para claves numéricas largas.</p>
 */
public final class BarCodeGenerator {

    /** Ancho en píxeles — se escala con {@code scaleToFit} en el RIDE. */
    private static final int DEFAULT_WIDTH = 400;

    /** Alto en píxeles — suficiente para que el barcode sea legible. */
    private static final int DEFAULT_HEIGHT = 80;

    private BarCodeGenerator() {}

    /**
     * Genera un código de barras Code 128 como bytes PNG a partir de la clave de acceso.
     *
     * @param accessKey clave de acceso de 49 dígitos
     * @return bytes PNG de la imagen del código de barras
     */
    public static byte[] generate(String accessKey) {
        return generate(accessKey, DEFAULT_WIDTH, DEFAULT_HEIGHT);
    }

    /**
     * Genera un código de barras Code 128 como bytes PNG con tamaño personalizado.
     *
     * @param accessKey clave de acceso de 49 dígitos
     * @param width     ancho en píxeles
     * @param height    alto en píxeles
     * @return bytes PNG de la imagen del código de barras
     */
    public static byte[] generate(String accessKey, int width, int height) {
        try {
            var writer = new Code128Writer();
            var hints = Map.<EncodeHintType, Object>of(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = writer.encode(accessKey, BarcodeFormat.CODE_128, width, height, hints);

            var outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new RideGenerationException(
                    "Failed to generate barcode for access key: " + accessKey, e);
        }
    }
}
