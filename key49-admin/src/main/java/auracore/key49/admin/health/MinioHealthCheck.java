package auracore.key49.admin.health;

import auracore.key49.storage.ObjectStorageService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Health check de readiness para MinIO/S3.
 *
 * <p>Verifica que el bucket configurado existe y es accesible.</p>
 */
@Readiness
@ApplicationScoped
public class MinioHealthCheck implements HealthCheck {

    @Inject
    ObjectStorageService storageService;

    @Override
    public HealthCheckResponse call() {
        try {
            var accessible = storageService.isBucketAccessible();
            return HealthCheckResponse.named("MinIO bucket")
                    .status(accessible)
                    .withData("bucket", storageService.getBucket())
                    .build();
        } catch (Exception e) {
            return HealthCheckResponse.named("MinIO bucket")
                    .down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
