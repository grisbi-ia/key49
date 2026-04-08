package auracore.key49.queue.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.enums.DocumentStatus;

class RetryPollerTest {

    @Test
    void shouldResolveSendRetryWhenNotYetSubmitted() {
        var doc = createDocument(DocumentStatus.RETRY);
        doc.sriSubmissionDate = null;

        assertEquals("doc.send", RetryPoller.resolveRetryEventType(doc));
    }

    @Test
    void shouldResolveAuthorizeRetryWhenAlreadySubmitted() {
        var doc = createDocument(DocumentStatus.RETRY);
        doc.sriSubmissionDate = Instant.now();

        assertEquals("doc.authorize", RetryPoller.resolveRetryEventType(doc));
    }

    @Test
    void shouldRetryExhaustedDocumentTransitionToFailed() {
        var doc = createDocument(DocumentStatus.RETRY);
        doc.retryCount = 6;
        doc.maxRetries = 6;

        // retryCount >= maxRetries → exhausted
        assertEquals(true, RetryDelayCalculator.isExhausted(doc.retryCount, doc.maxRetries));
    }

    @Test
    void shouldNotExhaustWhenRetriesRemain() {
        var doc = createDocument(DocumentStatus.RETRY);
        doc.retryCount = 3;
        doc.maxRetries = 6;

        assertEquals(false, RetryDelayCalculator.isExhausted(doc.retryCount, doc.maxRetries));
    }

    @Test
    void shouldResolveCorrectEventTypeBasedOnSubmissionDate() {
        var doc1 = createDocument(DocumentStatus.RETRY);
        doc1.sriSubmissionDate = null;
        assertEquals("doc.send", RetryPoller.resolveRetryEventType(doc1));

        var doc2 = createDocument(DocumentStatus.RETRY);
        doc2.sriSubmissionDate = Instant.now().minusSeconds(60);
        assertEquals("doc.authorize", RetryPoller.resolveRetryEventType(doc2));
    }

    @Test
    void shouldHandleRetrySequenceCorrectly() {
        // Simulate: SIGNED → RETRY(1) → SENT → RETRY(2) → etc
        var doc = createDocument(DocumentStatus.SIGNED);
        doc.retryCount = 0;
        doc.maxRetries = 6;

        // First failure: retryCount++ → 1, not exhausted
        doc.retryCount++;
        assertEquals(false, RetryDelayCalculator.isExhausted(doc.retryCount, doc.maxRetries));
        assertEquals(5, RetryDelayCalculator.calculateDelay(doc.retryCount).toSeconds());

        // Second failure: retryCount++ → 2, not exhausted
        doc.retryCount++;
        assertEquals(false, RetryDelayCalculator.isExhausted(doc.retryCount, doc.maxRetries));
        assertEquals(15, RetryDelayCalculator.calculateDelay(doc.retryCount).toSeconds());

        // Third: 45s
        doc.retryCount++;
        assertEquals(45, RetryDelayCalculator.calculateDelay(doc.retryCount).toSeconds());

        // Fourth: 135s
        doc.retryCount++;
        assertEquals(135, RetryDelayCalculator.calculateDelay(doc.retryCount).toSeconds());

        // Fifth: 405s
        doc.retryCount++;
        assertEquals(405, RetryDelayCalculator.calculateDelay(doc.retryCount).toSeconds());

        // Sixth: exhausted
        doc.retryCount++;
        assertEquals(true, RetryDelayCalculator.isExhausted(doc.retryCount, doc.maxRetries));
    }

    @Test
    void shouldValidateRetryToAuthorizedTransition() {
        // RETRY → AUTHORIZED should be valid (needed for authorization retries)
        assertEquals(true, DocumentStatus.RETRY.canTransitionTo(DocumentStatus.AUTHORIZED));
    }

    @Test
    void shouldValidateRetryToSentTransition() {
        assertEquals(true, DocumentStatus.RETRY.canTransitionTo(DocumentStatus.SENT));
    }

    @Test
    void shouldValidateRetryToFailedTransition() {
        assertEquals(true, DocumentStatus.RETRY.canTransitionTo(DocumentStatus.FAILED));
    }

    private Document createDocument(DocumentStatus status) {
        var doc = new Document();
        doc.id = UUID.randomUUID();
        doc.status = status;
        doc.retryCount = 0;
        doc.maxRetries = 6;
        doc.createdAt = Instant.now();
        doc.updatedAt = Instant.now();
        return doc;
    }
}
