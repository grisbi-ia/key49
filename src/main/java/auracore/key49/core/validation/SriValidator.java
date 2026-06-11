package auracore.key49.core.validation;

/**
 * Validadores de identificación ecuatoriana: RUC (módulo 11) y cédula (módulo 10).
 */
public final class SriValidator {

    private SriValidator() {
    }

    /**
     * Valida un RUC ecuatoriano (13 dígitos, módulo 11 para persona natural/jurídica/pública).
     */
    public static boolean isValidRuc(String ruc) {
        if (ruc == null || !ruc.matches("^\\d{13}$")) {
            return false;
        }
        // Los últimos 3 dígitos deben ser 001
        if (!ruc.endsWith("001")) {
            return false;
        }
        // El tercer dígito determina el tipo de contribuyente
        int thirdDigit = ruc.charAt(2) - '0';
        if (thirdDigit < 0 || thirdDigit > 9) {
            return false;
        }
        if (thirdDigit <= 5) {
            // Persona natural: validar con módulo 10 (cédula en los primeros 10 dígitos)
            return isValidCedula(ruc.substring(0, 10));
        } else if (thirdDigit == 6) {
            // Entidad pública: coeficientes [3,2,7,6,5,4,3,2], módulo 11, dígito verificador en posición 8
            return validateModulo11(ruc, new int[]{3, 2, 7, 6, 5, 4, 3, 2}, 8);
        } else if (thirdDigit == 9) {
            // Persona jurídica: coeficientes [4,3,2,7,6,5,4,3,2], módulo 11
            // Algunos RUC tienen dígito verificador en posición 9, otros en 10
            return validateModulo11(ruc, new int[]{4, 3, 2, 7, 6, 5, 4, 3, 2}, 9)
                    || validateModulo11(ruc, new int[]{4, 3, 2, 7, 6, 5, 4, 3, 2}, 10);
        }
        // Tercer dígito 7 u 8 no son válidos
        return false;
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

    private static boolean validateModulo11(String number, int[] coefficients, int checkDigitPos) {
        int sum = 0;
        for (int i = 0; i < coefficients.length; i++) {
            sum += (number.charAt(i) - '0') * coefficients[i];
        }
        int remainder = sum % 11;
        int checkDigit = (remainder == 0) ? 0 : 11 - remainder;
        if (checkDigit == 10 || checkDigit == 11) {
            checkDigit = 0;
        }
        return checkDigit == (number.charAt(checkDigitPos) - '0');
    }
}
