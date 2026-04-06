package auracore.key49.queue.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.model.enums.SriEnvironment;
import auracore.key49.sri.model.SriMessage;

class ConsumerHelperTest {

    @Test
    void shouldResolveTestEnvironment() {
        assertEquals(SriEnvironment.TEST, SignConsumer.resolveEnvironment("test"));
    }

    @Test
    void shouldResolveProductionEnvironment() {
        assertEquals(SriEnvironment.PRODUCTION, SignConsumer.resolveEnvironment("production"));
    }

    @Test
    void shouldDefaultToTestForUnknownEnvironment() {
        assertEquals(SriEnvironment.TEST, SignConsumer.resolveEnvironment("unknown"));
    }

    @Test
    void shouldExtractFirstErrorCode() {
        var messages = List.of(
                new SriMessage("INFO1", "Info message", null, "INFORMATIVO"),
                new SriMessage("52", "Invalid structure", null, "ERROR"),
                new SriMessage("45", "Date out of range", null, "ERROR"));

        assertEquals("52", SendConsumer.extractFirstErrorCode(messages));
    }

    @Test
    void shouldReturnNullWhenNoErrors() {
        var messages = List.of(
                new SriMessage("INFO1", "Info message", null, "INFORMATIVO"));

        assertEquals(null, SendConsumer.extractFirstErrorCode(messages));
    }

    @Test
    void shouldExtractErrorSummary() {
        var messages = List.of(
                new SriMessage("52", "Invalid structure", null, "ERROR"),
                new SriMessage("45", "Date out of range", null, "ERROR"));

        var summary = SendConsumer.extractErrorSummary(messages);
        assertEquals("[52] Invalid structure; [45] Date out of range", summary);
    }

    @Test
    void shouldReturnUnknownWhenNoErrorMessages() {
        var messages = List.<SriMessage>of();
        assertEquals("Unknown error", SendConsumer.extractErrorSummary(messages));
    }

    @Test
    void shouldValidateStateMachineTransitions() {
        // SignConsumer transitions
        assertEquals(true, DocumentStatus.CREATED.canTransitionTo(DocumentStatus.SIGNED));
        assertEquals(true, DocumentStatus.CREATED.canTransitionTo(DocumentStatus.FAILED));
        assertEquals(false, DocumentStatus.CREATED.canTransitionTo(DocumentStatus.SENT));

        // SendConsumer transitions
        assertEquals(true, DocumentStatus.SIGNED.canTransitionTo(DocumentStatus.SENT));
        assertEquals(true, DocumentStatus.SIGNED.canTransitionTo(DocumentStatus.RETRY));
        assertEquals(true, DocumentStatus.SIGNED.canTransitionTo(DocumentStatus.REJECTED));
        assertEquals(false, DocumentStatus.SIGNED.canTransitionTo(DocumentStatus.FAILED));

        // SENT → RECEIVED
        assertEquals(true, DocumentStatus.SENT.canTransitionTo(DocumentStatus.RECEIVED));

        // AuthorizeConsumer transitions
        assertEquals(true, DocumentStatus.RECEIVED.canTransitionTo(DocumentStatus.AUTHORIZED));
        assertEquals(true, DocumentStatus.RECEIVED.canTransitionTo(DocumentStatus.REJECTED));
        assertEquals(true, DocumentStatus.RECEIVED.canTransitionTo(DocumentStatus.RETRY));

        // NotifyConsumer transitions
        assertEquals(true, DocumentStatus.AUTHORIZED.canTransitionTo(DocumentStatus.NOTIFIED));
        assertEquals(true, DocumentStatus.AUTHORIZED.canTransitionTo(DocumentStatus.VOIDED));

        // RETRY transitions
        assertEquals(true, DocumentStatus.RETRY.canTransitionTo(DocumentStatus.SIGNED));
        assertEquals(true, DocumentStatus.RETRY.canTransitionTo(DocumentStatus.SENT));
        assertEquals(true, DocumentStatus.RETRY.canTransitionTo(DocumentStatus.AUTHORIZED));
        assertEquals(true, DocumentStatus.RETRY.canTransitionTo(DocumentStatus.FAILED));

        // Terminal states have no transitions
        assertEquals(true, DocumentStatus.REJECTED.isTerminal());
        assertEquals(true, DocumentStatus.FAILED.isTerminal());
        assertEquals(true, DocumentStatus.VOIDED.isTerminal());
    }

    @Test
    void shouldClassifySriBusinessErrors() {
        // Business error codes: 35, 45, 52, 65
        assertEquals(true, new SriMessage("35", "Already registered", null, "ERROR").isBusinessError());
        assertEquals(true, new SriMessage("45", "Date out of range", null, "ERROR").isBusinessError());
        assertEquals(true, new SriMessage("52", "Invalid structure", null, "ERROR").isBusinessError());
        assertEquals(true, new SriMessage("65", "Future date", null, "ERROR").isBusinessError());

        // Non-business error codes (should be retried)
        assertEquals(false, new SriMessage("43", "Duplicate key", null, "ERROR").isBusinessError());
        assertEquals(false, new SriMessage("99", "Unknown error", null, "ERROR").isBusinessError());

        // Non-error types
        assertEquals(false, new SriMessage("35", "Info", null, "INFORMATIVO").isBusinessError());
        assertEquals(false, new SriMessage("52", "Warning", null, "ADVERTENCIA").isBusinessError());
    }
}
