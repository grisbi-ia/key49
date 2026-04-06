package auracore.key49.admin.health;

import auracore.key49.sri.config.SriEndpoints;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Health check de liveness para el servicio SOAP de Autorización del SRI.
 *
 * <p>Ejecuta un HTTP HEAD al WSDL del SRI para verificar que el servicio está accesible.</p>
 */
@Liveness
@ApplicationScoped
public class SriAuthorizationHealthCheck implements HealthCheck {

    private static final Logger log = Logger.getLogger(SriAuthorizationHealthCheck.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @ConfigProperty(name = "key49.sri.environment", defaultValue = "test")
    String environment;

    @Override
    public HealthCheckResponse call() {
        var url = SriEndpoints.authorizationUrl(
                auracore.key49.core.model.enums.SriEnvironment.valueOf(environment.toUpperCase()));
        try {
            var client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .build();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            var up = response.statusCode() < 500;
            return HealthCheckResponse.named("SRI Autorización")
                    .status(up)
                    .withData("url", url)
                    .withData("statusCode", response.statusCode())
                    .build();
        } catch (Exception e) {
            log.debugf("SRI Autorización health check failed: %s", e.getMessage());
            return HealthCheckResponse.named("SRI Autorización")
                    .down()
                    .withData("url", url)
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
