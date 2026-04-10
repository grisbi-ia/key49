package auracore.key49.api.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests unitarios para EndpointCategory.
 */
class EndpointCategoryTest {

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void writeMethods(String method) {
        assertEquals(EndpointCategory.WRITE, EndpointCategory.fromHttpMethod(method));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS"})
    void readMethods(String method) {
        assertEquals(EndpointCategory.READ, EndpointCategory.fromHttpMethod(method));
    }

    @Test
    void caseInsensitive() {
        assertEquals(EndpointCategory.WRITE, EndpointCategory.fromHttpMethod("post"));
        assertEquals(EndpointCategory.READ, EndpointCategory.fromHttpMethod("get"));
    }

    @Test
    void keySuffix() {
        assertEquals("write", EndpointCategory.WRITE.keySuffix());
        assertEquals("read", EndpointCategory.READ.keySuffix());
    }
}
