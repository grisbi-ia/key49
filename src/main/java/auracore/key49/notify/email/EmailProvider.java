package auracore.key49.notify.email;

/**
 * Proveedor de envío de correo electrónico.
 *
 * <ul>
 *   <li>{@link #SMTP} — Jakarta Mail contra servidor SMTP (propio del tenant o compartido de Key49).</li>
 *   <li>{@link #PLUNK} — API REST de Plunk (useplunk.com). Incluye validación del destino
 *       y rastreo de eventos automático.</li>
 * </ul>
 */
public enum EmailProvider {
    SMTP,
    PLUNK
}
