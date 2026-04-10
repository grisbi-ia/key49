package auracore.key49.notify.webhook;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Validates webhook URLs to prevent Server-Side Request Forgery (SSRF). Rejects
 * URLs targeting private/internal networks, loopback, link-local, and non-HTTPS
 * schemes in production.
 */
public final class WebhookUrlValidator {

    private WebhookUrlValidator() {
    }

    public static void validate(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Webhook URL is required");
        }

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid webhook URL format");
        }

        var scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("https") && !scheme.equals("http"))) {
            throw new IllegalArgumentException("Webhook URL must use HTTPS or HTTP scheme");
        }

        var host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Webhook URL must have a valid host");
        }

        if (isBlockedHost(host)) {
            throw new IllegalArgumentException("Webhook URL targets a blocked network address");
        }

        try {
            var addresses = InetAddress.getAllByName(host);
            for (var addr : addresses) {
                if (isBlockedAddress(addr)) {
                    throw new IllegalArgumentException("Webhook URL resolves to a blocked network address");
                }
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Webhook URL host cannot be resolved");
        }
    }

    private static boolean isBlockedHost(String host) {
        var lower = host.toLowerCase();
        return lower.equals("localhost")
                || lower.endsWith(".local")
                || lower.endsWith(".internal")
                || lower.equals("[::1]");
    }

    private static boolean isBlockedAddress(InetAddress addr) {
        return addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isAnyLocalAddress()
                || isCloudMetadata(addr);
    }

    /**
     * AWS/GCP/Azure metadata endpoint: 169.254.169.254
     */
    private static boolean isCloudMetadata(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        return bytes.length == 4
                && (bytes[0] & 0xFF) == 169
                && (bytes[1] & 0xFF) == 254
                && (bytes[2] & 0xFF) == 169
                && (bytes[3] & 0xFF) == 254;
    }
}
