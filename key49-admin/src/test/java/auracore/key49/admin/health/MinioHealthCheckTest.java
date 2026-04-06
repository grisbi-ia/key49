package auracore.key49.admin.health;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para MinioHealthCheck.
 */
class MinioHealthCheckTest {

    @Test
    void shouldReturnUpWhenBucketAccessible() {
        var healthCheck = new MinioHealthCheck();
        healthCheck.storageService = new StubObjectStorageService(true, "key49-documents");

        var response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals("MinIO bucket", response.getName());
        assertEquals("key49-documents", response.getData().get().get("bucket"));
    }

    @Test
    void shouldReturnDownWhenBucketNotAccessible() {
        var healthCheck = new MinioHealthCheck();
        healthCheck.storageService = new StubObjectStorageService(false, "key49-documents");

        var response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }

    @Test
    void shouldReturnDownOnException() {
        var healthCheck = new MinioHealthCheck();
        healthCheck.storageService = new StubObjectStorageService(null, "key49-documents");

        var response = healthCheck.call();

        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertTrue(response.getData().get().containsKey("error"));
    }

    /**
     * Stub de ObjectStorageService para tests unitarios.
     */
    static class StubObjectStorageService extends auracore.key49.storage.ObjectStorageService {

        private final Boolean accessible;
        private final String bucketName;

        StubObjectStorageService(Boolean accessible, String bucketName) {
            this.accessible = accessible;
            this.bucketName = bucketName;
        }

        @Override
        public boolean isBucketAccessible() {
            if (accessible == null) {
                throw new RuntimeException("MinIO connection refused");
            }
            return accessible;
        }

        @Override
        public String getBucket() {
            return bucketName;
        }
    }
}
