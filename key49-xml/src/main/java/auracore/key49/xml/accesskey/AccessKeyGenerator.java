package auracore.key49.xml.accesskey;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.core.model.enums.SriEnvironment;

/**
 * Generador de clave de acceso de 49 dígitos para comprobantes electrónicos del SRI.
 *
 * <p>Estructura (49 dígitos):
 * <pre>
 * [fecha 8][tipoDoc 2][ruc 13][ambiente 1][serie 6][secuencial 9][codNumerico 8][tipoEmision 1][verificador 1]
 * </pre>
 *
 * <p>El dígito verificador se calcula con módulo 11 (pesos cíclicos 2-7 de derecha a izquierda).
 */
public final class AccessKeyGenerator {

    /** Longitud total de la clave de acceso. */
    public static final int ACCESS_KEY_LENGTH = 49;

    /** Tipo de emisión normal. */
    public static final String EMISSION_TYPE_NORMAL = "1";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("ddMMyyyy");
    private static final int[] MODULO_11_WEIGHTS = {2, 3, 4, 5, 6, 7};
    private static final SecureRandom RANDOM = new SecureRandom();

    private AccessKeyGenerator() {
    }

    /**
     * Genera una clave de acceso de 49 dígitos.
     *
     * @param issueDate     fecha de emisión (debe ser hoy en zona Ecuador)
     * @param documentType  tipo de comprobante
     * @param ruc           RUC del emisor (13 dígitos)
     * @param environment   ambiente SRI (pruebas/producción)
     * @param establishment establecimiento (3 dígitos)
     * @param issuePoint    punto de emisión (3 dígitos)
     * @param sequenceNumber secuencial (9 dígitos, zero-padded)
     * @return clave de acceso de 49 dígitos
     * @throws IllegalArgumentException si algún parámetro es inválido
     */
    public static String generate(LocalDate issueDate,
                                  DocumentType documentType,
                                  String ruc,
                                  SriEnvironment environment,
                                  String establishment,
                                  String issuePoint,
                                  String sequenceNumber) {
        validateInputs(issueDate, ruc, establishment, issuePoint, sequenceNumber);

        var numericCode = generateNumericCode();

        return generate(issueDate, documentType, ruc, environment,
                establishment, issuePoint, sequenceNumber, numericCode);
    }

    /**
     * Genera una clave de acceso con código numérico específico (para testing o reproducibilidad).
     *
     * @param numericCode código numérico de 8 dígitos
     */
    public static String generate(LocalDate issueDate,
                                  DocumentType documentType,
                                  String ruc,
                                  SriEnvironment environment,
                                  String establishment,
                                  String issuePoint,
                                  String sequenceNumber,
                                  String numericCode) {
        validateInputs(issueDate, ruc, establishment, issuePoint, sequenceNumber);
        validateNumericCode(numericCode);

        var base = new StringBuilder(48)
                .append(issueDate.format(DATE_FORMAT))       // 8
                .append(documentType.sriCode())              // 2
                .append(ruc)                                 // 13
                .append(environment.sriCode())               // 1
                .append(establishment)                       // 3
                .append(issuePoint)                          // 3
                .append(sequenceNumber)                      // 9
                .append(numericCode)                         // 8
                .append(EMISSION_TYPE_NORMAL)                // 1
                .toString();                                 // = 48

        int checkDigit = calculateModulo11(base);
        return base + checkDigit;
    }

    /**
     * Valida que una clave de acceso de 49 dígitos tenga el dígito verificador correcto.
     *
     * @param accessKey clave de acceso completa (49 dígitos)
     * @return true si la clave es válida
     */
    public static boolean isValid(String accessKey) {
        if (accessKey == null || accessKey.length() != ACCESS_KEY_LENGTH) {
            return false;
        }
        if (!accessKey.matches("\\d{49}")) {
            return false;
        }

        var base = accessKey.substring(0, 48);
        int expectedCheckDigit = accessKey.charAt(48) - '0';
        int actualCheckDigit = calculateModulo11(base);

        return expectedCheckDigit == actualCheckDigit;
    }

    /**
     * Extrae los componentes de una clave de acceso.
     *
     * @param accessKey clave de acceso de 49 dígitos
     * @return componentes desglosados
     * @throws IllegalArgumentException si la clave no tiene 49 dígitos
     */
    public static AccessKeyComponents parse(String accessKey) {
        if (accessKey == null || accessKey.length() != ACCESS_KEY_LENGTH || !accessKey.matches("\\d{49}")) {
            throw new IllegalArgumentException("Access key must be exactly 49 digits");
        }

        return new AccessKeyComponents(
                accessKey.substring(0, 8),   // fecha ddmmaaaa
                accessKey.substring(8, 10),  // tipo doc
                accessKey.substring(10, 23), // ruc
                accessKey.substring(23, 24), // ambiente
                accessKey.substring(24, 27), // establecimiento
                accessKey.substring(27, 30), // punto emisión
                accessKey.substring(30, 39), // secuencial
                accessKey.substring(39, 47), // código numérico
                accessKey.substring(47, 48), // tipo emisión
                accessKey.substring(48, 49)  // dígito verificador
        );
    }

    /**
     * Calcula el dígito verificador módulo 11 con pesos cíclicos 2-7.
     *
     * <p>Algoritmo:
     * <ol>
     *   <li>Recorrer dígitos de derecha a izquierda</li>
     *   <li>Multiplicar cada dígito por peso cíclico [2,3,4,5,6,7,2,3,...]</li>
     *   <li>Sumar todos los productos</li>
     *   <li>Residuo = suma mod 11</li>
     *   <li>Verificador = 11 - residuo</li>
     *   <li>Si verificador es 11 → 0, si es 10 → 1</li>
     * </ol>
     */
    static int calculateModulo11(String digits) {
        int sum = 0;
        for (int i = digits.length() - 1, w = 0; i >= 0; i--, w++) {
            int digit = digits.charAt(i) - '0';
            int weight = MODULO_11_WEIGHTS[w % MODULO_11_WEIGHTS.length];
            sum += digit * weight;
        }

        int remainder = sum % 11;
        int checkDigit = 11 - remainder;

        if (checkDigit == 11) return 0;
        if (checkDigit == 10) return 1;
        return checkDigit;
    }

    /**
     * Genera un código numérico aleatorio de 8 dígitos.
     */
    static String generateNumericCode() {
        int code = RANDOM.nextInt(100_000_000);
        return String.format("%08d", code);
    }

    private static void validateInputs(LocalDate issueDate,
                                       String ruc,
                                       String establishment,
                                       String issuePoint,
                                       String sequenceNumber) {
        if (issueDate == null) {
            throw new IllegalArgumentException("Issue date is required");
        }

        var today = LocalDate.now(Key49Constants.EC_ZONE);
        if (!issueDate.equals(today)) {
            throw new IllegalArgumentException(
                    "Issue date must be today (%s) but was %s".formatted(today, issueDate));
        }

        if (ruc == null || !ruc.matches("\\d{13}")) {
            throw new IllegalArgumentException("RUC must be exactly 13 digits");
        }

        if (establishment == null || !establishment.matches("\\d{3}")) {
            throw new IllegalArgumentException("Establishment must be exactly 3 digits");
        }

        if (issuePoint == null || !issuePoint.matches("\\d{3}")) {
            throw new IllegalArgumentException("Issue point must be exactly 3 digits");
        }

        if (sequenceNumber == null || !sequenceNumber.matches("\\d{9}")) {
            throw new IllegalArgumentException("Sequence number must be exactly 9 digits");
        }
    }

    private static void validateNumericCode(String numericCode) {
        if (numericCode == null || !numericCode.matches("\\d{8}")) {
            throw new IllegalArgumentException("Numeric code must be exactly 8 digits");
        }
    }

    /**
     * Componentes desglosados de una clave de acceso.
     *
     * @param date           fecha ddmmaaaa
     * @param documentType   código tipo documento (2 dígitos)
     * @param ruc            RUC emisor (13 dígitos)
     * @param environment    ambiente (1=pruebas, 2=producción)
     * @param establishment  establecimiento (3 dígitos)
     * @param issuePoint     punto de emisión (3 dígitos)
     * @param sequenceNumber secuencial (9 dígitos)
     * @param numericCode    código numérico (8 dígitos)
     * @param emissionType   tipo emisión (1=normal)
     * @param checkDigit     dígito verificador (1 dígito)
     */
    public record AccessKeyComponents(
            String date,
            String documentType,
            String ruc,
            String environment,
            String establishment,
            String issuePoint,
            String sequenceNumber,
            String numericCode,
            String emissionType,
            String checkDigit
    ) {
    }
}
