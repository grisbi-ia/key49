package auracore.key49.ride.generator;

/**
 * Excepción lanzada cuando falla la generación del RIDE (PDF).
 */
public class RideGenerationException extends RuntimeException {

    public RideGenerationException(String message) {
        super(message);
    }

    public RideGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
