package auracore.key49.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ObjectStorageServiceTest {

    private static final String BUCKET = "test-bucket";
    private static final String TENANT = "tenant_xyz";
    private static final String ACCESS_KEY = "2506202501099271531200110010020000000011234567813";
    private static final String DOC_TYPE = "01";
    private static final LocalDate ISSUE_DATE = LocalDate.of(2025, 6, 20);

    private MinioClient minioClient;
    private ObjectStorageService service;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        service = new ObjectStorageService(minioClient, BUCKET);
    }

    // --- store() ---

    @Test
    void storeShouldReturnObjectPath() throws Exception {
        var data = "<xml>test</xml>".getBytes(StandardCharsets.UTF_8);

        var path = service.store(TENANT, ISSUE_DATE, DOC_TYPE, ACCESS_KEY,
                DocumentArtifact.UNSIGNED_XML, data);

        var expectedPath = StoragePath.build(TENANT, ISSUE_DATE, DOC_TYPE, ACCESS_KEY,
                DocumentArtifact.UNSIGNED_XML);
        assertEquals(expectedPath, path);
    }

    @Test
    void storeShouldCallPutObject() throws Exception {
        var data = "<xml>signed</xml>".getBytes(StandardCharsets.UTF_8);

        service.store(TENANT, ISSUE_DATE, DOC_TYPE, ACCESS_KEY,
                DocumentArtifact.SIGNED_XML, data);

        var captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());

        var args = captor.getValue();
        assertEquals(BUCKET, args.bucket());
        assertEquals(StoragePath.build(TENANT, ISSUE_DATE, DOC_TYPE, ACCESS_KEY,
                DocumentArtifact.SIGNED_XML), args.object());
    }

    @Test
    void storeShouldThrowStorageExceptionOnError() throws Exception {
        doThrow(new RuntimeException("Connection refused"))
                .when(minioClient).putObject(any(PutObjectArgs.class));

        var data = "test".getBytes(StandardCharsets.UTF_8);

        var ex = assertThrows(StorageException.class,
                () -> service.store(TENANT, ISSUE_DATE, DOC_TYPE, ACCESS_KEY,
                        DocumentArtifact.UNSIGNED_XML, data));

        assertTrue(ex.getMessage().contains("Failed to store artifact"));
    }

    // --- retrieve() ---

    @Test
    void retrieveShouldReturnData() throws Exception {
        var expectedData = "<xml>authorized</xml>".getBytes(StandardCharsets.UTF_8);
        var objectPath = "tenant_xyz/2025/06/01/" + ACCESS_KEY + "/authorized.xml";

        var mockResponse = mock(GetObjectResponse.class);
        when(mockResponse.readAllBytes()).thenReturn(expectedData);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        var result = service.retrieve(objectPath);

        assertArrayEquals(expectedData, result);
    }

    @Test
    void retrieveShouldThrowStorageExceptionWhenNotFound() throws Exception {
        var objectPath = "tenant_xyz/2025/06/01/" + ACCESS_KEY + "/unsigned.xml";

        var errorResponse = new ErrorResponse("NoSuchKey", "Not found", "", "", "", "", "");
        var errorException = new ErrorResponseException(errorResponse, null, "");
        when(minioClient.getObject(any(GetObjectArgs.class))).thenThrow(errorException);

        var ex = assertThrows(StorageException.class, () -> service.retrieve(objectPath));

        assertTrue(ex.getMessage().contains("Artifact not found"));
    }

    @Test
    void retrieveShouldWrapGenericException() throws Exception {
        var objectPath = "some/path/file.xml";

        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new RuntimeException("Network error"));

        var ex = assertThrows(StorageException.class, () -> service.retrieve(objectPath));

        assertTrue(ex.getMessage().contains("Failed to retrieve artifact"));
    }

    // --- exists() ---

    @Test
    void existsShouldReturnTrueWhenObjectExists() throws Exception {
        var objectPath = "tenant_xyz/2025/06/01/" + ACCESS_KEY + "/signed.xml";
        when(minioClient.statObject(any(StatObjectArgs.class)))
                .thenReturn(mock(StatObjectResponse.class));

        assertTrue(service.exists(objectPath));
    }

    @Test
    void existsShouldReturnFalseWhenObjectNotFound() throws Exception {
        var objectPath = "tenant_xyz/2025/06/01/" + ACCESS_KEY + "/signed.xml";

        var errorResponse = new ErrorResponse("NoSuchKey", "Not found", "", "", "", "", "");
        var errorException = new ErrorResponseException(errorResponse, null, "");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(errorException);

        assertFalse(service.exists(objectPath));
    }

    @Test
    void existsShouldThrowStorageExceptionOnOtherErrors() throws Exception {
        var objectPath = "some/path/file.xml";

        var errorResponse = new ErrorResponse("AccessDenied", "Forbidden", "", "", "", "", "");
        var errorException = new ErrorResponseException(errorResponse, null, "");
        when(minioClient.statObject(any(StatObjectArgs.class))).thenThrow(errorException);

        assertThrows(StorageException.class, () -> service.exists(objectPath));
    }

    // --- delete() ---

    @Test
    void deleteShouldCallRemoveObject() throws Exception {
        var objectPath = "tenant_xyz/2025/06/01/" + ACCESS_KEY + "/ride.pdf";

        service.delete(objectPath);

        var captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient).removeObject(captor.capture());

        assertEquals(BUCKET, captor.getValue().bucket());
        assertEquals(objectPath, captor.getValue().object());
    }

    @Test
    void deleteShouldThrowStorageExceptionOnError() throws Exception {
        var objectPath = "some/path/file.xml";
        doThrow(new RuntimeException("Delete failed"))
                .when(minioClient).removeObject(any(RemoveObjectArgs.class));

        assertThrows(StorageException.class, () -> service.delete(objectPath));
    }

    // --- isBucketAccessible() ---

    @Test
    void isBucketAccessibleShouldReturnTrueWhenBucketExists() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

        assertTrue(service.isBucketAccessible());
    }

    @Test
    void isBucketAccessibleShouldReturnFalseWhenBucketDoesNotExist() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

        assertFalse(service.isBucketAccessible());
    }

    @Test
    void isBucketAccessibleShouldReturnFalseOnException() throws Exception {
        when(minioClient.bucketExists(any(BucketExistsArgs.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        assertFalse(service.isBucketAccessible());
    }
}
