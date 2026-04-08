package auracore.key49.admin.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.jboss.logging.Logger;

import auracore.key49.sri.config.SriEndpoints;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Health check de liveness para el servicio SOAP de Recepción del SRI.
 *
 * <p>
 * Ejecuta un HTTP HEAD al WSDL del SRI para verificar que el servicio está
 * accesible.</p>
 */
@Liveness
@ApplicationScoped
public class SriReceptionHealthCheck implements HealthCheck {

    private static final Logger log = Logger.getLogger(SriReceptionHealthCheck.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @ConfigProperty(name = "key49.sri.environment", defaultValue = "test")
    String environment;

    @Override
    public HealthCheckResponse call() {
        var url = SriEndpoints.receptionUrl(
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
            return HealthCheckResponse.named("SRI Recepción")
                    .status(up)
                    .withData("url", url)
                    .withData("statusCode", response.statusCode())
                    .build();
        } catch (Exception e) {
            log.debugf("SRI Recepción health check failed: %s", e.getMessage());
            return HealthCheckResponse.named("SRI Recepción")
                    .down()
                    .withData("url", url)
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
