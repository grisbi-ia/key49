package auracore.key49.core.validation;

/**
 * Validadores de identificación ecuatoriana: RUC (módulo 11) y cédula (módulo 10).
 */
public final class SriValidator {

    private SriValidator() {
    }

    /**
     * Valida un RUC ecuatoriano.
     *
     * <p>Valida únicamente la <b>estructura</b>: 13 dígitos, terminación
     * {@code 001} y tercer dígito de tipo de contribuyente válido (0-5 persona
     * natural, 6 entidad pública, 9 persona jurídica).</p>
     *
     * <p><b>No</b> valida el dígito verificador con módulo 11. El SRI comunicó
     * oficialmente (Programa de optimización de generación y validación del
     * número de RUC) que existen RUC válidos y activos que <b>no</b> cumplen
     * módulo 11 y que esa validación ya no debe aplicarse, recomendando validar
     * contra la fuente de datos. Como Key49 no consulta el padrón del SRI, delega
     * esa verificación al propio SRI al recibir el comprobante; de lo contrario
     * se generan falsos negativos (p. ej. {@code 1793200847001}, CLICK
     * SOLUCIONES S.A.S., activo en el SRI).</p>
     */
    public static boolean isValidRuc(String ruc) {
        if (ruc == null || !ruc.matches("^\\d{13}$")) {
            return false;
        }
        // Los últimos 3 dígitos deben ser 001
        if (!ruc.endsWith("001")) {
            return false;
        }
        // Tercer dígito = tipo de contribuyente: 0-5 natural, 6 pública, 9 jurídica.
        int thirdDigit = ruc.charAt(2) - '0';
        return thirdDigit <= 6 || thirdDigit == 9;
    }

    /**
     * Valida una cédula ecuatoriana (10 dígitos, módulo 10).
     */
    public static boolean isValidCedula(String cedula) {
        if (cedula == null || !cedula.matches("^\\d{10}$")) {
            return false;
        }
        // Código de provincia: primeros 2 dígitos, entre 01 y 24 (o 30 para ecuatorianos en el exterior)
        int province = Integer.parseInt(cedula.substring(0, 2));
        if (province < 1 || (province > 24 && province != 30)) {
            return false;
        }
        // Tercer dígito debe ser 0-5 para persona natural
        int thirdDigit = cedula.charAt(2) - '0';
        if (thirdDigit > 5) {
            return false;
        }

        int sum = 0;
        for (int i = 0; i < 9; i++) {
            int digit = cedula.charAt(i) - '0';
            if (i % 2 == 0) {
                // Posiciones impares (0-indexed pares): multiplicar por 2
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
        }
        int checkDigit = (10 - (sum % 10)) % 10;
        return checkDigit == (cedula.charAt(9) - '0');
    }

    /**
     * Valida un identificador según su tipo SRI.
     *
     * @param idType código SRI del tipo de identificación ("04"=RUC, "05"=cédula, "06"=pasaporte, "07"=consumidor final)
     * @param id     el valor del identificador
     */
    public static boolean isValidIdentification(String idType, String id) {
        if (idType == null || id == null || id.isBlank()) {
            return false;
        }
        return switch (idType) {
            case "04" -> isValidRuc(id);
            case "05" -> isValidCedula(id);
            case "06" -> id.length() >= 3 && id.length() <= 20;
            case "07" -> "9999999999999".equals(id);
            default -> false;
        };
    }

    /**
     * Valida establishment (3 dígitos numéricos).
     */
    public static boolean isValidEstablishment(String establishment) {
        return establishment != null && establishment.matches("^\\d{3}$");
    }

    /**
     * Valida issue point (3 dígitos numéricos).
     */
    public static boolean isValidIssuePoint(String issuePoint) {
        return issuePoint != null && issuePoint.matches("^\\d{3}$");
    }

    /**
     * Valida sequence number (9 dígitos numéricos).
     */
    public static boolean isValidSequenceNumber(String sequenceNumber) {
        return sequenceNumber != null && sequenceNumber.matches("^\\d{9}$");
    }
}
