package auracore.key49.storage;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Perfil de test que apunta MinIO a un endpoint no accesible. Garantiza que las
 * llamadas a ObjectStorageService siempre fallen, independientemente de si
 * docker-compose está corriendo.
 */
public class NoMinioTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "key49.storage.endpoint", "http://localhost:1",
                "key49.storage.connect-timeout-seconds", "1");
    }
}
