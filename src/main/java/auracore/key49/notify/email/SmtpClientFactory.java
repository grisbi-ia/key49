package auracore.key49.notify.email;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import auracore.key49.core.model.Tenant;
import auracore.key49.signer.CertificateEncryptor;
import io.vertx.core.Vertx;
import io.vertx.ext.mail.MailClient;
import io.vertx.ext.mail.MailConfig;
import io.vertx.ext.mail.StartTLSOptions;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Fábrica de clientes SMTP por tenant con caché LRU.
 *
 * <p>
 * Si el tenant tiene {@code smtpEnabled = true}, construye un
 * {@link MailClient} con la configuración SMTP del tenant. La contraseña se
 * descifra al momento de construir el cliente (no se cachea en claro).
 *
 * <p>
 * Si la caché no contiene el tenant, se crea un nuevo cliente. Si la caché está
 * llena, se cierra el cliente más antiguo (LRU eviction).
 */
@ApplicationScoped
public class SmtpClientFactory {

    private static final int DEFAULT_MAX_CACHE_SIZE = 50;

    @Inject
    Logger log;

    @Inject
    Vertx vertx;

    @ConfigProperty(name = "key49.master-key")
    Optional<String> masterKeyBase64;

    private final Map<UUID, CachedClient> cache;

    public SmtpClientFactory() {
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, CachedClient> eldest) {
                if (size() > DEFAULT_MAX_CACHE_SIZE) {
                    log.debugf("SmtpClientFactory: evicting cached client for tenant %s",
                            eldest.getKey());
                    eldest.getValue().client().close();
                    return true;
                }
                return false;
            }
        };
    }

    /**
     * Obtiene o crea un {@link MailClient} para el tenant.
     *
     * @param tenant el tenant con configuración SMTP
     * @return el cliente SMTP configurado
     * @throws IllegalStateException si la clave maestra no está configurada
     * @throws IllegalArgumentException si la configuración SMTP del tenant es
     * incompleta
     */
    public MailClient getOrCreate(Tenant tenant) {
        synchronized (cache) {
            var cached = cache.get(tenant.id);
            if (cached != null && cached.configHash() == computeConfigHash(tenant)) {
                return cached.client();
            }
            // Config changed or not cached — create new client
            if (cached != null) {
                log.infof("SmtpClientFactory: SMTP config changed for tenant %s, recreating client",
                        tenant.id);
                cached.client().close();
            }
            var client = buildClient(tenant);
            cache.put(tenant.id, new CachedClient(client, computeConfigHash(tenant)));
            log.infof("SmtpClientFactory: created SMTP client for tenant %s (%s:%d)",
                    tenant.id, tenant.smtpHost, tenant.smtpPort);
            return client;
        }
    }

    /**
     * Invalida el cliente cacheado de un tenant (tras cambio de configuración).
     */
    public void invalidate(UUID tenantId) {
        synchronized (cache) {
            var removed = cache.remove(tenantId);
            if (removed != null) {
                removed.client().close();
                log.infof("SmtpClientFactory: invalidated cached client for tenant %s", tenantId);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (cache) {
            cache.values().forEach(c -> c.client().close());
            cache.clear();
        }
    }

    private MailClient buildClient(Tenant tenant) {
        if (tenant.smtpHost == null || tenant.smtpHost.isBlank()) {
            throw new IllegalArgumentException("SMTP host not configured for tenant " + tenant.id);
        }
        if (tenant.smtpPort == null) {
            throw new IllegalArgumentException("SMTP port not configured for tenant " + tenant.id);
        }

        var config = new MailConfig()
                .setHostname(tenant.smtpHost)
                .setPort(tenant.smtpPort);

        if (tenant.smtpPort == 465) {
            config.setSsl(true);
        } else if (tenant.smtpPort == 587) {
            config.setStarttls(StartTLSOptions.REQUIRED);
        }

        if (tenant.smtpUser != null && tenant.smtpPasswordEnc != null
                && tenant.smtpPasswordEnc.length > 0) {
            var masterKey = CertificateEncryptor.decodeMasterKey(
                    masterKeyBase64.orElseThrow(()
                            -> new IllegalStateException("KEY49_MASTER_KEY not configured")));
            var passwordChars = CertificateEncryptor.decryptPassword(
                    tenant.smtpPasswordEnc, masterKey);
            config.setUsername(tenant.smtpUser);
            config.setPassword(new String(passwordChars));
            Arrays.fill(passwordChars, (char) 0);
        }

        return MailClient.create(vertx, config);
    }

    private static int computeConfigHash(Tenant tenant) {
        int hash = 17;
        hash = 31 * hash + (tenant.smtpHost != null ? tenant.smtpHost.hashCode() : 0);
        hash = 31 * hash + (tenant.smtpPort != null ? tenant.smtpPort : 0);
        hash = 31 * hash + (tenant.smtpUser != null ? tenant.smtpUser.hashCode() : 0);
        hash = 31 * hash + (tenant.smtpFrom != null ? tenant.smtpFrom.hashCode() : 0);
        hash = 31 * hash + Arrays.hashCode(tenant.smtpPasswordEnc);
        return hash;
    }

    private record CachedClient(MailClient client, int configHash) {

    }
}
