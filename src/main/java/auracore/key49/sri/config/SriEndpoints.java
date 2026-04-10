package auracore.key49.sri.config;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import auracore.key49.core.model.enums.SriEnvironment;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * URLs de los servicios SOAP del SRI por ambiente.
 *
 * <p>
 * Ambiente de pruebas (celcer) y producción (cel). Las URLs son configurables
 * vía variables de entorno para permitir overrides en entornos de test o
 * staging.
 */
@ApplicationScoped
public class SriEndpoints {

    @ConfigProperty(name = "key49.sri.url.test.reception",
            defaultValue = "https://celcer.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline?wsdl")
    String testReceptionUrl;

    @ConfigProperty(name = "key49.sri.url.test.authorization",
            defaultValue = "https://celcer.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline?wsdl")
    String testAuthorizationUrl;

    @ConfigProperty(name = "key49.sri.url.production.reception",
            defaultValue = "https://cel.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline?wsdl")
    String prodReceptionUrl;

    @ConfigProperty(name = "key49.sri.url.production.authorization",
            defaultValue = "https://cel.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline?wsdl")
    String prodAuthorizationUrl;

    /**
     * URL del servicio SOAP de Recepción de Comprobantes.
     */
    public String receptionUrl(SriEnvironment environment) {
        return switch (environment) {
            case TEST ->
                testReceptionUrl;
            case PRODUCTION ->
                prodReceptionUrl;
        };
    }

    /**
     * URL del servicio SOAP de Autorización de Comprobantes.
     */
    public String authorizationUrl(SriEnvironment environment) {
        return switch (environment) {
            case TEST ->
                testAuthorizationUrl;
            case PRODUCTION ->
                prodAuthorizationUrl;
        };
    }
}
