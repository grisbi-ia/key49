package auracore.key49.sri.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import auracore.key49.core.model.enums.SriEnvironment;

/**
 * Tests unitarios para SriEndpoints.
 */
class SriEndpointsTest {

    @Test
    @DisplayName("URL de recepción en ambiente TEST apunta a celcer")
    void receptionUrlTest() {
        var url = SriEndpoints.receptionUrl(SriEnvironment.TEST);
        assertTrue(url.contains("celcer.sri.gob.ec"));
        assertTrue(url.contains("RecepcionComprobantesOffline"));
    }

    @Test
    @DisplayName("URL de recepción en ambiente PRODUCTION apunta a cel")
    void receptionUrlProduction() {
        var url = SriEndpoints.receptionUrl(SriEnvironment.PRODUCTION);
        assertTrue(url.contains("cel.sri.gob.ec"));
        assertFalse(url.contains("celcer"));
        assertTrue(url.contains("RecepcionComprobantesOffline"));
    }

    @Test
    @DisplayName("URL de autorización en ambiente TEST apunta a celcer")
    void authorizationUrlTest() {
        var url = SriEndpoints.authorizationUrl(SriEnvironment.TEST);
        assertTrue(url.contains("celcer.sri.gob.ec"));
        assertTrue(url.contains("AutorizacionComprobantesOffline"));
    }

    @Test
    @DisplayName("URL de autorización en ambiente PRODUCTION apunta a cel")
    void authorizationUrlProduction() {
        var url = SriEndpoints.authorizationUrl(SriEnvironment.PRODUCTION);
        assertTrue(url.contains("cel.sri.gob.ec"));
        assertFalse(url.contains("celcer"));
        assertTrue(url.contains("AutorizacionComprobantesOffline"));
    }
}
