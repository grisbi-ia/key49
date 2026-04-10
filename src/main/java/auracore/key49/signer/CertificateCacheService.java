package auracore.key49.signer;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Caché en memoria de certificados .p12 ya parseados (PrivateKey +
 * X509Certificate). Evita el costoso descifrado AES + parsing PKCS12 en cada
 * firma de documento.
 *
 * <p>
 * Key: tenant_id (UUID). Valor: CertificateData con PrivateKey, certificado y
 * cadena completa. TTL configurable (default 30 min), máximo de entradas
 * configurable (default 100).
 */

@ApplicationScoped
public class CertificateCacheService {

    @Inject
    Logger log;

    @ConfigProperty(name = "key49.master-key")
    Optional<String> masterKeyBase64;

    @ConfigProperty(name = "key49.certificate-cache.ttl-minutes",
            defaultValue = "30")
    long ttlMinutes;

    @ConfigProperty(name = "key49.certificate-cache.max-entries",
            defaultValue = "100")
    int maxEntries;

    private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    record CacheEntry(XAdESBESSigner.CertificateData data, Instant cachedAt) {
    }

    /**
     * Obtiene los datos del certificado desde la caché o los carga
     * (descifra + parsea PKCS12) si no están en caché o expiraron.
     *
     * @param tenantId         UUID del tenant
     * @param encryptedP12     certificado .p12 cifrado con AES-256-GCM
     * @param encryptedPassword contraseña cifrada del certificado
     * @return datos del certificado parseados (PrivateKey + X509Certificate + chain)
     */
    public XAdESBESSigner.CertificateData getOrLoad(UUID tenantId,
            byte[] encryptedP12, byte[] encryptedPassword) {
        var entry = cache.get(tenantId);
        if (entry != null && !isExpired(entry)) {
            log.debugf("CertificateCache: hit for tenant %s", tenantId);
            return entry.data();
        }

        log.infof("CertificateCache: miss for tenant %s, loading certificate", tenantId);
        var masterKey = CertificateEncryptor.decodeMasterKey(
                masterKeyBase64.orElseThrow(()
                        -> new IllegalStateException("KEY49_MASTER_KEY not configured")));
        var p12Bytes = CertificateEncryptor.decrypt(encryptedP12, masterKey);
        var password = CertificateEncryptor.decryptPassword(
                encryptedPassword, masterKey);

        var certData = XAdESBESSigner.loadCertificateData(p12Bytes, password);

        Arrays.fill(p12Bytes, (byte) 0);
        Arrays.fill(password, '\0');
        Arrays.fill(masterKey, (byte) 0);

        cache.put(tenantId, new CacheEntry(certData, Instant.now()));
        evictIfNeeded();

        return certData;
    }

    /**
     * Invalida la entrada de caché para un tenant (ej: al subir nuevo certificado).
     */
    public void invalidate(UUID tenantId) {
        var removed = cache.remove(tenantId);
        if (removed != null) {
            log.infof("CertificateCache: invalidated tenant %s", tenantId);
        }
    }

    /**
     * Retorna el número de entradas actualmente en la caché.
     */
    public int size() {
        return cache.size();
    }

    private boolean isExpired(CacheEntry entry) {
        return Duration.between(entry.cachedAt(), Instant.now()).toMinutes() >= ttlMinutes;
    }

    private void evictIfNeeded() {
        while (cache.size() > maxEntries) {
            cache.entrySet().stream()
                    .min(Comparator.comparing(e -> e.getValue().cachedAt()))
                    .ifPresent(e -> cache.remove(e.getKey()));
        }
    }
}
