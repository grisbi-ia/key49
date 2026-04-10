package auracore.key49.queue.consumer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Rastrea la cantidad de mensajes en vuelo (in-flight) por cada consumer
 * RabbitMQ. Utilizado por {@link GracefulShutdownObserver} para reportar el
 * estado de los consumers al momento del shutdown.
 */
@ApplicationScoped
public class InFlightTracker {

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * Registra el inicio de procesamiento de un mensaje en el consumer dado.
     */
    public void increment(String consumerName) {
        counters.computeIfAbsent(consumerName, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * Registra la finalización del procesamiento de un mensaje en el consumer
     * dado.
     */
    public void decrement(String consumerName) {
        var counter = counters.get(consumerName);
        if (counter != null) {
            counter.decrementAndGet();
        }
    }

    /**
     * Retorna un snapshot inmutable con la cantidad de mensajes en vuelo por
     * consumer.
     */
    public Map<String, Integer> snapshot() {
        return counters.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().get()));
    }

    /**
     * Retorna el total de mensajes en vuelo en todos los consumers.
     */
    public int totalInFlight() {
        return counters.values().stream().mapToInt(AtomicInteger::get).sum();
    }
}


 