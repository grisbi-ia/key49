package auracore.key49.api.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import auracore.key49.api.resource.SystemStatusResource.ComponentStatus;

/**
 * Tests unitarios para SystemStatusResource.
 */

class SystemStatusResourceTest {

    @Nested
    class ToComponentStatus {

        @Test
        void shouldMapUpToOperational() {
            var hcr = HealthCheckResponse.named("Test").up().build();
            var result = SystemStatusResource.toComponentStatus(hcr);

            assertEquals("operational", result.status());
            assertEquals("Test", result.name());
        }

        @Test
        void shouldMapDownToDown() {
            var hcr = HealthCheckResponse.named("Test").down().build();
            var result = SystemStatusResource.toComponentStatus(hcr);

            assertEquals("down", result.status());
            assertEquals("Test", result.name());
        }

        @Test
        void shouldIncludeDataFromHealthCheck() {
            var hcr = HealthCheckResponse.named("SRI")
                    .up()
                    .withData("url", "https://sri.gob.ec")
                    .withData("statusCode", 200)
                    .build();
            var result = SystemStatusResource.toComponentStatus(hcr);

            assertEquals("operational", result.status());
            assertTrue(result.details().containsKey("url"));
            assertTrue(result.details().containsKey("statusCode"));
        }

        @Test
        void shouldHandleNoData() {
            var hcr = HealthCheckResponse.named("Empty").up().build();
            var result = SystemStatusResource.toComponentStatus(hcr);

            assertNotNull(result.details());
        }
    }

    @Nested
    class ResolveOverall {

        @Test
        void shouldReturnOperationalWhenAllUp() {
            var components = new LinkedHashMap<String, ComponentStatus>();
            components.put("a", new ComponentStatus("operational", "A", Map.of()));
            components.put("b", new ComponentStatus("operational", "B", Map.of()));

            assertEquals("operational", SystemStatusResource.resolveOverall(components));
        }

        @Test
        void shouldReturnOutageWhenAnyDown() {
            var components = new LinkedHashMap<String, ComponentStatus>();
            components.put("a", new ComponentStatus("operational", "A", Map.of()));
            components.put("b", new ComponentStatus("down", "B", Map.of()));

            assertEquals("outage", SystemStatusResource.resolveOverall(components));
        }

        @Test
        void shouldReturnOutageWhenAllDown() {
            var components = new LinkedHashMap<String, ComponentStatus>();
            components.put("a", new ComponentStatus("down", "A", Map.of()));
            components.put("b", new ComponentStatus("down", "B", Map.of()));

            assertEquals("outage", SystemStatusResource.resolveOverall(components));
        }

        @Test
        void shouldReturnOperationalWithEmptyMap() {
            assertEquals("operational",
                    SystemStatusResource.resolveOverall(new LinkedHashMap<>()));
        }
    }

    @Nested
    class ComponentStatusRecord {

        @Test
        void shouldPreserveValues() {
            var details = Map.<String, Object>of("key", "value");
            var cs = new ComponentStatus("operational", "Test", details);

            assertEquals("operational", cs.status());
            assertEquals("Test", cs.name());
            assertEquals("value", cs.details().get("key"));
        }
    }
}
