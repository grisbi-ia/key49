package auracore.key49.notify.webhook;

/**
 * Excepción lanzada cuando falla el envío del webhook.
 */
public class WebhookException extends RuntimeException {

    public WebhookException(String message) {
        super(message);
    }

    public WebhookException(String message, Throwable cause) {
        super(message, cause);
    }
}
