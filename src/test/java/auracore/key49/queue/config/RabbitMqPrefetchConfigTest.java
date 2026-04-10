package auracore.key49.queue.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Verifica que el prefetch de cada consumer RabbitMQ esté configurado
 * con los valores diferenciados según la latencia de cada operación.
 */
@QuarkusTest
class RabbitMqPrefetchConfigTest {

    @ConfigProperty(name = "mp.messaging.incoming.doc-sign-in.rabbitmq-prefetch")
    int signPrefetch;

    @ConfigProperty(name = "mp.messaging.incoming.doc-send-in.rabbitmq-prefetch")
    int sendPrefetch;

    @ConfigProperty(name = "mp.messaging.incoming.doc-authorize-in.rabbitmq-prefetch")
    int authorizePrefetch;

    @ConfigProperty(name = "mp.messaging.incoming.doc-notify-in.rabbitmq-prefetch")
    int notifyPrefetch;

    @ConfigProperty(name = "mp.messaging.incoming.doc-dlq-in.rabbitmq-prefetch")
    int dlqPrefetch;

    @Test
    @DisplayName("Prefetch de firma debe ser 10 (CPU-bound, buffering beneficioso)")
    void signPrefetchShouldBe10() {
        assertEquals(10, signPrefetch);
    }

    @Test
    @DisplayName("Prefetch de envío debe ser 5 (SOAP SRI lento)")
    void sendPrefetchShouldBe5() {
        assertEquals(5, sendPrefetch);
    }

    @Test
    @DisplayName("Prefetch de autorización debe ser 5 (SOAP SRI lento)")
    void authorizePrefetchShouldBe5() {
        assertEquals(5, authorizePrefetch);
    }

    @Test
    @DisplayName("Prefetch de notificación debe ser 10 (email+webhook rápidos)")
    void notifyPrefetchShouldBe10() {
        assertEquals(10, notifyPrefetch);
    }

    @Test
    @DisplayName("Prefetch DLQ debe ser 5 (procesamiento de errores sin urgencia)")
    void dlqPrefetchShouldBe5() {
        assertEquals(5, dlqPrefetch);
    }

    @Test
    @DisplayName("Consumers de SRI deben tener prefetch menor que firma y notificación")
    void sriConsumersShouldHaveLowerPrefetch() {
        assertTrue(sendPrefetch < signPrefetch,
                "Send prefetch should be lower than sign prefetch");
        assertTrue(authorizePrefetch < notifyPrefetch,
                "Authorize prefetch should be lower than notify prefetch");
    }

    @Test
    @DisplayName("Todos los prefetch deben ser positivos")
    void allPrefetchShouldBePositive() {
        assertTrue(signPrefetch > 0, "Sign prefetch must be positive");
        assertTrue(sendPrefetch > 0, "Send prefetch must be positive");
        assertTrue(authorizePrefetch > 0, "Authorize prefetch must be positive");
        assertTrue(notifyPrefetch > 0, "Notify prefetch must be positive");
        assertTrue(dlqPrefetch > 0, "DLQ prefetch must be positive");
    }
}
