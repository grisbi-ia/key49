package auracore.key49.signer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collections;

/**
 * Extrae metadata de un certificado PKCS#12 (.p12) para almacenar en la tabla tenants.
 *
 * <p>Permite verificar la validez del certificado y extraer: subject, serial, fecha de expiración
 * e issuer sin necesidad de realizar una firma.
 */
public final class CertificateMetadataExtractor {

    private CertificateMetadataExtractor() {
    }

    /**
     * Extrae metadata del certificado .p12.
     *
     * @param p12Bytes contenido binario del archivo .p12
     * @param password contraseña del certificado
     * @return metadata extraída del certificado
     * @throws SigningException si el archivo no es un PKCS#12 válido, la contraseña es incorrecta,
     *                          o no se encuentra un certificado con clave privada
     */
    public static CertificateMetadata extract(byte[] p12Bytes, char[] password) {
        if (p12Bytes == null || p12Bytes.length == 0) {
            throw new SigningException("Certificate file is empty or null");
        }
        if (password == null || password.length == 0) {
            throw new SigningException("Certificate password is empty or null");
        }

        try {
            var keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new ByteArrayInputStream(p12Bytes), password);

            var alias = findSigningAlias(keyStore);
            var certificate = (X509Certificate) keyStore.getCertificate(alias);

            if (certificate == null) {
                throw new SigningException("No X.509 certificate found for alias: " + alias);
            }

            var subject = certificate.getSubjectX500Principal().getName();
            var serial = certificate.getSerialNumber().toString(16).toUpperCase();
            var expiresAt = certificate.getNotAfter().toInstant();
            var issuer = certificate.getIssuerX500Principal().getName();
            var isValid = expiresAt.isAfter(Instant.now());

            return new CertificateMetadata(subject, serial, expiresAt, issuer, isValid);
        } catch (SigningException e) {
            throw e;
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException | CertificateException e) {
            throw new SigningException("Failed to read PKCS#12 certificate — wrong password or invalid file", e);
        }
    }

    private static String findSigningAlias(KeyStore keyStore) throws KeyStoreException {
        var aliases = Collections.list(keyStore.aliases());
        for (var alias : aliases) {
            if (keyStore.isKeyEntry(alias)) {
                return alias;
            }
        }
        throw new SigningException("No key entry found in PKCS#12 keystore");
    }

    /**
     * Metadata extraída de un certificado .p12.
     *
     * @param subject   nombre distinguido del sujeto (DN)
     * @param serial    número de serie en hexadecimal
     * @param expiresAt fecha de expiración del certificado
     * @param issuer    nombre distinguido del emisor (DN)
     * @param valid     {@code true} si el certificado no ha expirado
     */
    public record CertificateMetadata(
            String subject,
            String serial,
            Instant expiresAt,
            String issuer,
            boolean valid) {

        /**
         * Días restantes hasta la expiración. Negativo si ya expiró.
         */
        public long daysUntilExpiration() {
            var now = Instant.now();
            return java.time.Duration.between(now, expiresAt).toDays();
        }
    }
}
