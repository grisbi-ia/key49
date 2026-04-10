package auracore.key49.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDate;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.jboss.logging.Logger;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Servicio de almacenamiento de artefactos de documentos electrónicos en MinIO
 * (S3-compatible).
 *
 * <p>
 * Almacena XML (sin firmar, firmado, autorizado) y RIDE (PDF) con la
 * estructura:
 * {@code {tenant_id}/{year}/{month}/{doc_type}/{access_key}/{filename}}</p>
 *
 * <p>
 * Política de retención: 7 años (configurada a nivel de bucket en MinIO).</p>
 */
@ApplicationScoped
public class ObjectStorageService {

    private static final Logger log = Logger.getLogger(ObjectStorageService.class);

    @ConfigProperty(name = "key49.storage.endpoint", defaultValue = "http://localhost:9000")
    String endpoint;

    @ConfigProperty(name = "key49.storage.access-key", defaultValue = "minioadmin")
    String accessKeyConfig;

    @ConfigProperty(name = "key49.storage.secret-key", defaultValue = "minioadmin")
    String secretKey;

    @ConfigProperty(name = "key49.storage.bucket", defaultValue = "key49-documents")
    String bucket;

    @ConfigProperty(name = "key49.storage.region", defaultValue = "us-east-1")
    String region;

    @ConfigProperty(name = "key49.storage.connect-timeout-seconds", defaultValue = "5")
    long connectTimeoutSeconds;

    @ConfigProperty(name = "key49.storage.write-timeout-seconds", defaultValue = "30")
    long writeTimeoutSeconds;

    @ConfigProperty(name = "key49.storage.read-timeout-seconds", defaultValue = "15")
    long readTimeoutSeconds;

    MinioClient client;

    @PostConstruct
    void init() {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKeyConfig, secretKey)
                .region(region)
                .build();
        client.setTimeout(
                connectTimeoutSeconds * 1000,
                writeTimeoutSeconds * 1000,
                readTimeoutSeconds * 1000);
        log.infof("MinIO storage initialized: endpoint=%s, bucket=%s, timeouts(connect=%ds, write=%ds, read=%ds)",
                endpoint, bucket, connectTimeoutSeconds, writeTimeoutSeconds, readTimeoutSeconds);
    }

    /**
     * Constructor para testing — permite inyectar un MinioClient mock.
     */
    ObjectStorageService(MinioClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    /**
     * Constructor CDI por defecto.
     */
    public ObjectStorageService() {
    }

    /**
     * Almacena un artefacto de documento en MinIO.
     *
     * @param tenantId identificador del tenant
     * @param issueDate fecha de emisión del documento
     * @param docTypeCode código SRI del tipo de documento
     * @param accessKey clave de acceso de 49 dígitos
     * @param artifact tipo de artefacto
     * @param data contenido del artefacto
     * @return ruta del objeto almacenado en MinIO
     */
    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 30000,
            successThreshold = 3)
    public String store(String tenantId, LocalDate issueDate, String docTypeCode,
            String accessKey, DocumentArtifact artifact, byte[] data) {
        var objectPath = StoragePath.build(tenantId, issueDate, docTypeCode, accessKey, artifact);

        try (var stream = new ByteArrayInputStream(data)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .stream(stream, data.length, -1)
                    .contentType(artifact.contentType())
                    .build());

            log.debugf("Stored artifact: bucket=%s, path=%s, size=%d bytes",
                    bucket, objectPath, data.length);
            return objectPath;
        } catch (Exception e) {
            throw new StorageException("Failed to store artifact: " + objectPath, e);
        }
    }

    /**
     * Descarga un artefacto de MinIO.
     *
     * @param objectPath ruta del objeto en MinIO
     * @return contenido del artefacto
     */
    @CircuitBreaker(
            requestVolumeThreshold = 10,
            failureRatio = 0.5,
            delay = 30000,
            successThreshold = 3)
    public byte[] retrieve(String objectPath) {
        try (InputStream stream = client.getObject(GetObjectArgs.builder()
                .bucket(bucket)
                .object(objectPath)
                .build())) {
            return stream.readAllBytes();
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                throw new StorageException("Artifact not found: " + objectPath, e);
            }
            throw new StorageException("Failed to retrieve artifact: " + objectPath, e);
        } catch (Exception e) {
            throw new StorageException("Failed to retrieve artifact: " + objectPath, e);
        }
    }

    /**
     * Verifica si un artefacto existe en MinIO.
     *
     * @param objectPath ruta del objeto en MinIO
     * @return true si el objeto existe
     */
    public boolean exists(String objectPath) {
        try {
            client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw new StorageException("Failed to check artifact existence: " + objectPath, e);
        } catch (Exception e) {
            throw new StorageException("Failed to check artifact existence: " + objectPath, e);
        }
    }

    /**
     * Elimina un artefacto de MinIO.
     *
     * @param objectPath ruta del objeto en MinIO
     */
    public void delete(String objectPath) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .build());
            log.debugf("Deleted artifact: bucket=%s, path=%s", bucket, objectPath);
        } catch (Exception e) {
            throw new StorageException("Failed to delete artifact: " + objectPath, e);
        }
    }

    /**
     * Verifica si el bucket configurado existe y es accesible.
     *
     * @return true si el bucket existe
     */
    public boolean isBucketAccessible() {
        try {
            return client.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucket)
                    .build());
        } catch (Exception e) {
            log.warnf("Bucket accessibility check failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Retorna el nombre del bucket configurado.
     */
    public String getBucket() {
        return bucket;
    }
}
