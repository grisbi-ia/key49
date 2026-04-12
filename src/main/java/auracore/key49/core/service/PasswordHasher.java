package auracore.key49.core.service;

import java.security.SecureRandom;

import org.bouncycastle.crypto.generators.OpenBSDBCrypt;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Utilidad para hashing de contraseñas con BCrypt (cost factor 12).
 *
 * <p>
 * Usa BouncyCastle {@link OpenBSDBCrypt} que ya es dependencia del proyecto
 * para firma XAdES-BES.</p>
 */
@ApplicationScoped
public class PasswordHasher {

    private static final int BCRYPT_COST = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Genera un hash BCrypt de la contraseña.
     *
     * @param password contraseña en texto plano
     * @return hash BCrypt (formato $2a$12$...)
     */
    public String hash(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return OpenBSDBCrypt.generate(password.toCharArray(), salt, BCRYPT_COST);
    }

    /**
     * Verifica una contraseña contra un hash BCrypt.
     *
     * @param password contraseña en texto plano
     * @param bcryptHash hash almacenado
     * @return true si la contraseña coincide
     */
    public boolean verify(String password, String bcryptHash) {
        if (password == null || bcryptHash == null) {
            return false;
        }
        return OpenBSDBCrypt.checkPassword(bcryptHash, password.toCharArray());
    }
}
