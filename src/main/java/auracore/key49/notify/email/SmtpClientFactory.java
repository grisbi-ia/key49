package auracore.key49.notify.email;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import auracore.key49.core.model.Tenant;
import auracore.key49.signer.CertificateEncryptor;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;

/**
 * Fábrica de sesiones SMTP (Jakarta Mail) por tenant con caché LRU.
 *
 * <p>
 * Si el tenant tiene {@code smtp_host} configurado, construye un {@link Session}
 * con la configuración SMTP del tenant. La contraseña se descifra al momento de
 * construir la sesión.
 *
 * <p>
 * Si la caché no contiene el tenant, se crea una nueva sesión. Si la caché está
 * llena, se desaloja la entrada más antigua (LRU eviction).
 */
@ApplicationScoped
public class SmtpClientFactory {

    private static final int DEFAULT_MAX_CACHE_SIZE = 50;

    @Inject
    Logger log;

    @ConfigProperty(name = "key49.master-key")
    Optional<String> masterKeyBase64;

    private final Map<UUID, CachedSession> cache;

    public SmtpClientFactory() {
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, CachedSession> eldest) {
                if (size() > DEFAULT_MAX_CACHE_SIZE) {
                    log.debugf("SmtpClientFactory: evicting cached session for tenant %s",
                            eldest.getKey());
                    return true;
                }
                return false;
            }
        };
    }

    /**
     * Obtiene o crea un {@link TenantSmtpSession} para el tenant.
     *
     * @param tenant el tenant con configuración SMTP
     * @return la sesión SMTP configurada con dirección de remitente
     * @throws IllegalStateException si la clave maestra no está configurada
     * @throws IllegalArgumentException si la configuración SMTP del tenant es
     * incompleta
     */
    public TenantSmtpSession getOrCreate(Tenant tenant) {
        synchronized (cache) {
            var cached = cache.get(tenant.id);
            if (cached != null && cached.configHash() == computeConfigHash(tenant)) {
                return cached.sessionInfo();
            }
            if (cached != null) {
                log.infof("SmtpClientFactory: SMTP config changed for tenant %s, recreating session",
                        tenant.id);
            }
            var sessionInfo = buildSession(tenant);
            cache.put(tenant.id, new CachedSession(sessionInfo, computeConfigHash(tenant)));
            log.infof("SmtpClientFactory: created SMTP session for tenant %s (%s:%d)",
                    tenant.id, tenant.smtpHost, tenant.smtpPort);
            return sessionInfo;
        }
    }

    /**
     * Invalida la sesión cacheada de un tenant (tras cambio de configuración).
     */
    public void invalidate(UUID tenantId) {
        synchronized (cache) {
            var removed = cache.remove(tenantId);
            if (removed != null) {
                log.infof("SmtpClientFactory: invalidated cached session for tenant %s", tenantId);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        synchronized (cache) {
            cache.clear();
        }
    }

    private TenantSmtpSession buildSession(Tenant tenant) {
        if (tenant.smtpHost == null || tenant.smtpHost.isBlank()) {
            throw new IllegalArgumentException("SMTP host not configured for tenant " + tenant.id);
        }
        if (tenant.smtpPort == null) {
            throw new IllegalArgumentException("SMTP port not configured for tenant " + tenant.id);
        }

        var props = new Properties();
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "30000");
        props.put("mail.smtp.writetimeout", "10000");

        if (tenant.smtpPort == 465) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.ssl.trust", "*");
            props.put("mail.smtp.socketFactory.port", String.valueOf(tenant.smtpPort));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        } else if (tenant.smtpPort == 587) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
            props.put("mail.smtp.ssl.trust", "*");
        }

        props.put("mail.smtp.host", tenant.smtpHost);
        props.put("mail.smtp.port", String.valueOf(tenant.smtpPort));

        Authenticator authenticator = null;
        if (tenant.smtpUser != null && tenant.smtpPasswordEnc != null
                && tenant.smtpPasswordEnc.length > 0) {
            props.put("mail.smtp.auth", "true");
            var masterKey = CertificateEncryptor.decodeMasterKey(
                    masterKeyBase64.orElseThrow(()
                            -> new IllegalStateException("KEY49_MASTER_KEY not configured")));
            var passwordChars = CertificateEncryptor.decryptPassword(
                    tenant.smtpPasswordEnc, masterKey);
            var password = new String(passwordChars);
            Arrays.fill(passwordChars, (char) 0);
            var user = tenant.smtpUser;
            authenticator = new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, password);
                }
            };
        }

        var session = Session.getInstance(props, authenticator);
        return new TenantSmtpSession(session, tenant.smtpFrom);
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

    public record TenantSmtpSession(Session session, String from) {

    }

    private record CachedSession(TenantSmtpSession sessionInfo, int configHash) {

    }
}
