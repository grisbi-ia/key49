package auracore.key49.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import auracore.key49.core.model.enums.DocumentStatus;

class DocumentTransitionTest {

    @ParameterizedTest
    @CsvSource({
            "CREATED, SIGNED",
            "SIGNED, SENT",
            "SENT, RECEIVED",
            "RECEIVED, AUTHORIZED",
            "AUTHORIZED, NOTIFIED",
            "NOTIFIED, VOIDED"
    })
    void shouldTransitionThroughHappyPath(DocumentStatus from, DocumentStatus to) {
        var doc = new Document();
        doc.status = from;

        doc.transitionTo(to);

        assertEquals(to, doc.status);
    }

    @Test
    void shouldThrowOnInvalidTransition() {
        var doc = new Document();
        doc.status = DocumentStatus.CREATED;

        var ex = assertThrows(InvalidStateTransitionException.class,
                () -> doc.transitionTo(DocumentStatus.AUTHORIZED));

        assertEquals(DocumentStatus.CREATED, ex.from());
        assertEquals(DocumentStatus.AUTHORIZED, ex.to());
        assertTrue(ex.getMessage().contains("CREATED"));
        assertTrue(ex.getMessage().contains("AUTHORIZED"));
    }

    @Test
    void shouldNotTransitionFromTerminalState() {
        var doc = new Document();
        doc.status = DocumentStatus.FAILED;

        assertThrows(InvalidStateTransitionException.class,
                () -> doc.transitionTo(DocumentStatus.RETRY));
    }

    @Test
    void shouldPreserveStateOnFailedTransition() {
        var doc = new Document();
        doc.status = DocumentStatus.REJECTED;

        assertThrows(InvalidStateTransitionException.class,
                () -> doc.transitionTo(DocumentStatus.CREATED));

        assertEquals(DocumentStatus.REJECTED, doc.status);
    }

    @Test
    void shouldAllowRetryToSigned() {
        var doc = new Document();
        doc.status = DocumentStatus.RETRY;

        doc.transitionTo(DocumentStatus.SIGNED);

        assertEquals(DocumentStatus.SIGNED, doc.status);
    }

    @Test
    void shouldAllowRetryToFailed() {
        var doc = new Document();
        doc.status = DocumentStatus.RETRY;

        doc.transitionTo(DocumentStatus.FAILED);

        assertEquals(DocumentStatus.FAILED, doc.status);
    }
}
