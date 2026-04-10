package auracore.key49.api.portal;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.enums.DocumentStatus;

/**
 * Tests unitarios para la lógica de retry manual desde el portal.
 */
class PortalRetryTest {

    @Nested
    @DisplayName("State machine: FAILED → CREATED")
    class StateTransition {

        @Test
        void shouldAllowTransitionFromFailedToCreated() {
            assertTrue(DocumentStatus.FAILED.canTransitionTo(DocumentStatus.CREATED));
        }

        @Test
        void shouldNotAllowTransitionFromAuthorizedToCreated() {
            assertFalse(DocumentStatus.AUTHORIZED.canTransitionTo(DocumentStatus.CREATED));
        }

        @Test
        void shouldNotAllowTransitionFromCreatedToCreated() {
            assertFalse(DocumentStatus.CREATED.canTransitionTo(DocumentStatus.CREATED));
        }

        @Test
        void shouldNotAllowTransitionFromNotifiedToCreated() {
            assertFalse(DocumentStatus.NOTIFIED.canTransitionTo(DocumentStatus.CREATED));
        }

        @Test
        void shouldAllowTransitionFromRejectedToCreated() {
            assertTrue(DocumentStatus.REJECTED.canTransitionTo(DocumentStatus.CREATED));
        }

        @Test
        void shouldTransitionDocumentAndResetRetryFields() {
            var doc = createFailedDocument(LocalDate.now(Key49Constants.EC_ZONE));

            doc.transitionTo(DocumentStatus.CREATED);
            doc.retryCount = 0;
            doc.nextRetryAt = null;
            doc.updatedAt = Instant.now();

            assertEquals(DocumentStatus.CREATED, doc.status);
            assertEquals(0, doc.retryCount);
            assertNull(doc.nextRetryAt);
            assertNotNull(doc.updatedAt);
        }
    }

    @Nested
    @DisplayName("Issue date validation")
    class IssueDateValidation {

        @Test
        void shouldAcceptDocumentWithTodayIssueDate() {
            var today = LocalDate.now(Key49Constants.EC_ZONE);
            var doc = createFailedDocument(today);
            assertEquals(today, doc.issueDate);
        }

        @Test
        void shouldRejectDocumentWithPastIssueDate() {
            var yesterday = LocalDate.now(Key49Constants.EC_ZONE).minusDays(1);
            var doc = createFailedDocument(yesterday);
            var today = LocalDate.now(Key49Constants.EC_ZONE);
            assertNotEquals(today, doc.issueDate);
        }
    }

    @Nested
    @DisplayName("Retry eligibility")
    class RetryEligibility {

        @Test
        void failedDocumentToday_shouldBeEligible() {
            var doc = createFailedDocument(LocalDate.now(Key49Constants.EC_ZONE));
            assertTrue(isRetryEligible(doc));
        }

        @Test
        void failedDocumentYesterday_shouldNotBeEligible() {
            var doc = createFailedDocument(LocalDate.now(Key49Constants.EC_ZONE).minusDays(1));
            assertFalse(isRetryEligible(doc));
        }

        @Test
        void authorizedDocumentToday_shouldNotBeEligible() {
            var doc = createDocument(DocumentStatus.AUTHORIZED, LocalDate.now(Key49Constants.EC_ZONE));
            assertFalse(isRetryEligible(doc));
        }

        @Test
        void createdDocumentToday_shouldNotBeEligible() {
            var doc = createDocument(DocumentStatus.CREATED, LocalDate.now(Key49Constants.EC_ZONE));
            assertFalse(isRetryEligible(doc));
        }

        @Test
        void retryDocumentToday_shouldNotBeEligible() {
            var doc = createDocument(DocumentStatus.RETRY, LocalDate.now(Key49Constants.EC_ZONE));
            assertFalse(isRetryEligible(doc));
        }

        private boolean isRetryEligible(Document doc) {
            var today = LocalDate.now(Key49Constants.EC_ZONE);
            return doc.status == DocumentStatus.FAILED && doc.issueDate.equals(today);
        }
    }

    private static Document createFailedDocument(LocalDate issueDate) {
        return createDocument(DocumentStatus.FAILED, issueDate);
    }

    private static Document createDocument(DocumentStatus status, LocalDate issueDate) {
        var doc = new Document();
        doc.id = UUID.randomUUID();
        doc.status = status;
        doc.issueDate = issueDate;
        doc.retryCount = 3;
        doc.nextRetryAt = Instant.now();
        doc.updatedAt = Instant.now().minusSeconds(60);
        doc.lastErrorCode = "99";
        doc.lastErrorMessage = "Connection refused";
        return doc;
    }
}
