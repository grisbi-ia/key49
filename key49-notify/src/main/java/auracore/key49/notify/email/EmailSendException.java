package auracore.key49.notify.email;

/**
 * Excepción lanzada cuando falla el envío de un email de notificación.
 */
public class EmailSendException extends RuntimeException {

    public EmailSendException(String message) {
        super(message);
    }

    public EmailSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
