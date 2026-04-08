package auracore.key49.admin.alert.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * Tests unitarios para DlqAlertRule.extractMessageCount().
 */
class DlqAlertRuleTest {

    @Test
    void extractMessageCountShouldParseValidJson() {
        var json = """
                {"messages_ready":2,"messages_unacknowledged":1,"messages":3,"name":"key49.dlq"}""";
        assertEquals(3, DlqAlertRule.extractMessageCount(json));
    }

    @Test
    void extractMessageCountShouldReturnZeroForEmptyQueue() {
        var json = """
                {"messages_ready":0,"messages_unacknowledged":0,"messages":0,"name":"key49.dlq"}""";
        assertEquals(0, DlqAlertRule.extractMessageCount(json));
    }

    @Test
    void extractMessageCountShouldReturnZeroWhenFieldMissing() {
        var json = """
                {"name":"key49.dlq","consumers":0}""";
        assertEquals(0, DlqAlertRule.extractMessageCount(json));
    }

    @Test
    void extractMessageCountShouldHandleWhitespace() {
        var json = """
                { "messages" : 42 }""";
        assertEquals(42, DlqAlertRule.extractMessageCount(json));
    }

    @Test
    void extractMessageCountShouldReturnZeroForMalformedJson() {
        assertEquals(0, DlqAlertRule.extractMessageCount("not json at all"));
    }

    @Test
    void extractMessageCountShouldReturnZeroForEmptyString() {
        assertEquals(0, DlqAlertRule.extractMessageCount(""));
    }

    @Test
    void extractMessageCountShouldHandleLargeNumber() {
        var json = """
                {"messages":999999}""";
        assertEquals(999999, DlqAlertRule.extractMessageCount(json));
    }
}
