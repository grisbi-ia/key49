package auracore.key49.queue.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.service.QuotaService;
import jakarta.persistence.EntityManager;

@ExtendWith(MockitoExtension.class)
class RetryPollerTest {

    @Mock
    Logger log;

    @Mock
    QuotaService quotaService;

    @InjectMocks
    RetryPoller retryPoller;

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

    @Test
    void shouldMarkFailedAndSignalNotificationWhenExhausted() {
        var doc = createDocument(DocumentStatus.RETRY);
        doc.retryCount = 6;
        doc.maxRetries = 6;
        var em = mock(EntityManager.class);

        var exhausted = retryPoller.markExhaustedIfNeeded(doc, em, "tenant_x");

        assertTrue(exhausted, "Debe señalizar notificación de fallo");
        assertEquals(DocumentStatus.FAILED, doc.status);
        verify(quotaService).releaseQuota(em, "tenant_x");
    }

    @Test
    void shouldNotMarkWhenRetriesRemain() {
        var doc = createDocument(DocumentStatus.RETRY);
        doc.retryCount = 1;
        doc.maxRetries = 6;

        var exhausted = retryPoller.markExhaustedIfNeeded(doc, mock(EntityManager.class), "tenant_x");

        assertFalse(exhausted);
        assertEquals(DocumentStatus.RETRY, doc.status);
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
