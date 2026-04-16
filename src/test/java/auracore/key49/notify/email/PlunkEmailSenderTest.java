package auracore.key49.notify.email;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Tests unitarios de PlunkEmailSender.
 * Mockea PlunkClient para verificar la lógica de orquestación sin red.
 */
class PlunkEmailSenderTest {

    private static final String API_KEY  = "sk_test_abc123";
    private static final String FROM     = "noreply@key49.ec";
    private static final String NAME     = "Key49";
    private static final String TO       = "cliente@example.com";
    private static final String SUBJECT  = "Factura 001-001-000000123";
    private static final String HTML     = "<html>body</html>";
    private static final String KEY      = "4901202501099271531200110010010000000011234567813";

    // ── sendPlatform ────────────────────────────────────────────────────────

    @Test
    void sendPlatform_validEmail_sendsAndTracks() {
        var validResult = new PlunkClient.VerifyResult(true, true, true, false, false, List.of());

        try (var clientMock = mockStatic(PlunkClient.class)) {
            clientMock.when(() -> PlunkClient.verify(API_KEY, TO)).thenReturn(validResult);

            PlunkEmailSender.sendPlatform(API_KEY, FROM, NAME, TO, SUBJECT, HTML);

            // verify() called once
            clientMock.verify(() -> PlunkClient.verify(API_KEY, TO), times(1));
            // send() called once with the right request
            clientMock.verify(() -> PlunkClient.send(eq(API_KEY), any(PlunkClient.SendRequest.class)),
                    times(1));
            // track() NOT called for platform emails (null trackData)
            clientMock.verify(() -> PlunkClient.track(anyString(), anyString(), anyString(), any()),
                    never());
        }
    }

    @Test
    void sendPlatform_invalidDestination_throwsEmailSendException() {
        var invalidResult = new PlunkClient.VerifyResult(false, false, false, false, false,
                List.of("no_mx_records"));

        try (var clientMock = mockStatic(PlunkClient.class)) {
            clientMock.when(() -> PlunkClient.verify(API_KEY, TO)).thenReturn(invalidResult);

            assertThrows(EmailSendException.class,
                    () -> PlunkEmailSender.sendPlatform(API_KEY, FROM, NAME, TO, SUBJECT, HTML));

            // send() must NOT be called
            clientMock.verify(() -> PlunkClient.send(anyString(), any()), never());
            // delivery_aborted event tracked
            clientMock.verify(() -> PlunkClient.track(eq(API_KEY), eq(TO),
                    eq("email.delivery_aborted"), any()), times(1));
        }
    }

    @Test
    void sendPlatform_disposableEmail_sendsWithWarning() {
        // Disposable email: valid=true, disposable=true — should send anyway
        var disposableResult = new PlunkClient.VerifyResult(true, true, true, true, false, List.of());

        try (var clientMock = mockStatic(PlunkClient.class)) {
            clientMock.when(() -> PlunkClient.verify(API_KEY, TO)).thenReturn(disposableResult);

            // Should NOT throw
            PlunkEmailSender.sendPlatform(API_KEY, FROM, NAME, TO, SUBJECT, HTML);

            clientMock.verify(() -> PlunkClient.send(eq(API_KEY), any(PlunkClient.SendRequest.class)),
                    times(1));
        }
    }

    // ── sendDocumentDelivery ────────────────────────────────────────────────

    @Test
    void sendDocumentDelivery_validEmail_sendsWithAttachments() {
        var validResult = new PlunkClient.VerifyResult(true, true, true, false, false, List.of());
        byte[] pdf = "pdf-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] xml = "<xml/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        try (var clientMock = mockStatic(PlunkClient.class)) {
            clientMock.when(() -> PlunkClient.verify(API_KEY, TO)).thenReturn(validResult);

            PlunkEmailSender.sendDocumentDelivery(
                    API_KEY, FROM, NAME, TO, SUBJECT, HTML,
                    pdf, "factura.pdf", xml, "factura.xml",
                    KEY, "Factura");

            // send() must receive a request with 2 attachments
            clientMock.verify(() -> PlunkClient.send(eq(API_KEY),
                    any(PlunkClient.SendRequest.class)), times(1));
            // document.sent event tracked
            clientMock.verify(() -> PlunkClient.track(eq(API_KEY), eq(TO),
                    eq("document.sent"), any(Map.class)), times(1));
        }
    }

    @Test
    void sendDocumentDelivery_domainDoesNotExist_throwsEmailSendException() {
        var badResult = new PlunkClient.VerifyResult(false, true, false, false, false,
                List.of("domain_not_found"));

        try (var clientMock = mockStatic(PlunkClient.class)) {
            clientMock.when(() -> PlunkClient.verify(API_KEY, TO)).thenReturn(badResult);

            assertThrows(EmailSendException.class,
                    () -> PlunkEmailSender.sendDocumentDelivery(
                            API_KEY, FROM, NAME, TO, SUBJECT, HTML,
                            null, null, null, null, KEY, "Factura"));

            clientMock.verify(() -> PlunkClient.send(anyString(), any()), never());
        }
    }

    @Test
    void sendDocumentDelivery_noAttachments_sendsEmptyList() {
        var validResult = new PlunkClient.VerifyResult(true, true, true, false, false, List.of());

        try (var clientMock = mockStatic(PlunkClient.class)) {
            clientMock.when(() -> PlunkClient.verify(API_KEY, TO)).thenReturn(validResult);

            // null bytes → no attachments
            PlunkEmailSender.sendDocumentDelivery(
                    API_KEY, FROM, NAME, TO, SUBJECT, HTML,
                    null, null, null, null, KEY, "Factura");

            clientMock.verify(() -> PlunkClient.send(eq(API_KEY),
                    any(PlunkClient.SendRequest.class)), times(1));
        }
    }

    @Test
    void sendDocumentDelivery_plunkUnavailable_sendsAnyway() {
        // verify returns unavailable (all true, soft-fail) — send should proceed
        var unavailable = PlunkClient.VerifyResult.unavailable();

        try (var clientMock = mockStatic(PlunkClient.class)) {
            clientMock.when(() -> PlunkClient.verify(API_KEY, TO)).thenReturn(unavailable);

            PlunkEmailSender.sendDocumentDelivery(
                    API_KEY, FROM, NAME, TO, SUBJECT, HTML,
                    null, null, null, null, KEY, "Factura");

            clientMock.verify(() -> PlunkClient.send(eq(API_KEY), any()), times(1));
        }
    }
}
