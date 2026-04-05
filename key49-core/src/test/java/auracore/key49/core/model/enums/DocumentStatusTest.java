package auracore.key49.core.model.enums;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class DocumentStatusTest {

    // ── Valid transitions ──

    @ParameterizedTest
    @CsvSource({
            "CREATED, SIGNED",
            "CREATED, FAILED",
            "SIGNED, SENT",
            "SIGNED, RETRY",
            "SIGNED, REJECTED",
            "SENT, RECEIVED",
            "RECEIVED, AUTHORIZED",
            "RECEIVED, REJECTED",
            "RECEIVED, RETRY",
            "AUTHORIZED, NOTIFIED",
            "AUTHORIZED, VOIDED",
            "NOTIFIED, VOIDED",
            "RETRY, SIGNED",
            "RETRY, SENT",
            "RETRY, FAILED"
    })
    void shouldAllowValidTransition(DocumentStatus from, DocumentStatus to) {
        assertTrue(from.canTransitionTo(to),
                "Expected %s -> %s to be valid".formatted(from, to));
    }

    // ── Invalid transitions ──

    @ParameterizedTest
    @CsvSource({
            "CREATED, AUTHORIZED",
            "CREATED, RECEIVED",
            "CREATED, VOIDED",
            "SIGNED, AUTHORIZED",
            "SENT, SIGNED",
            "SENT, FAILED",
            "AUTHORIZED, CREATED",
            "AUTHORIZED, SIGNED",
            "NOTIFIED, CREATED",
            "NOTIFIED, AUTHORIZED",
            "REJECTED, CREATED",
            "REJECTED, SIGNED",
            "FAILED, CREATED",
            "FAILED, RETRY",
            "VOIDED, CREATED",
            "VOIDED, NOTIFIED"
    })
    void shouldRejectInvalidTransition(DocumentStatus from, DocumentStatus to) {
        assertFalse(from.canTransitionTo(to),
                "Expected %s -> %s to be invalid".formatted(from, to));
    }

    // ── Terminal states ──

    @ParameterizedTest
    @EnumSource(value = DocumentStatus.class, names = {"REJECTED", "FAILED", "VOIDED"})
    void terminalStatesShouldHaveNoTransitions(DocumentStatus status) {
        assertTrue(status.isTerminal());
        for (DocumentStatus target : DocumentStatus.values()) {
            assertFalse(status.canTransitionTo(target));
        }
    }

    @ParameterizedTest
    @EnumSource(value = DocumentStatus.class, names = {"CREATED", "SIGNED", "SENT", "RECEIVED", "AUTHORIZED", "RETRY"})
    void nonTerminalStatesShouldNotBeTerminal(DocumentStatus status) {
        assertFalse(status.isTerminal());
    }

    // ── NOTIFIED is special: can transition to VOIDED but considered semi-terminal ──

    @Test
    void notifiedShouldOnlyTransitionToVoided() {
        assertFalse(DocumentStatus.NOTIFIED.isTerminal());
        assertTrue(DocumentStatus.NOTIFIED.canTransitionTo(DocumentStatus.VOIDED));

        for (DocumentStatus target : DocumentStatus.values()) {
            if (target != DocumentStatus.VOIDED) {
                assertFalse(DocumentStatus.NOTIFIED.canTransitionTo(target));
            }
        }
    }
}
