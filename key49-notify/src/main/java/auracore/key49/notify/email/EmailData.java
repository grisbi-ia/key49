package auracore.key49.notify.email;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Datos necesarios para construir el email de entrega de un comprobante electrónico.
 *
 * @param issuerName     razón social del emisor
 * @param issuerRuc      RUC del emisor
 * @param recipientName  nombre del receptor
 * @param recipientEmails lista de emails de destino (separados por ; en la BD)
 * @param documentType   tipo de documento legible ("Factura", "Nota de Crédito", etc.)
 * @param documentNumber número completo (establishment-issuePoint-sequenceNumber)
 * @param accessKey      clave de acceso de 49 dígitos
 * @param issueDate      fecha de emisión
 * @param totalAmount    monto total del comprobante
 * @param currency       moneda (ej: "DOLAR")
 * @param ridePdf        bytes del RIDE (PDF) — puede ser null si no hay RIDE
 * @param authorizedXml  bytes del XML autorizado — puede ser null si no hay XML
 */
public record EmailData(
        String issuerName,
        String issuerRuc,
        String recipientName,
        List<String> recipientEmails,
        String documentType,
        String documentNumber,
        String accessKey,
        LocalDate issueDate,
        BigDecimal totalAmount,
        String currency,
        byte[] ridePdf,
        byte[] authorizedXml
) {

    /**
     * Genera el nombre del archivo PDF para adjuntar al email.
     * Formato: {accessKey}.pdf
     */
    public String rideFilename() {
        return accessKey + ".pdf";
    }

    /**
     * Genera el nombre del archivo XML para adjuntar al email.
     * Formato: {accessKey}.xml
     */
    public String xmlFilename() {
        return accessKey + ".xml";
    }

    /**
     * Parsea un string de emails separados por ; en una lista.
     *
     * @param rawEmails string con emails separados por ;
     * @return lista de emails sin espacios en blanco
     */
    public static List<String> parseEmails(String rawEmails) {
        if (rawEmails == null || rawEmails.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(rawEmails.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
