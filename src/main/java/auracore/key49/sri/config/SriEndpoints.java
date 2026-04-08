package auracore.key49.sri.config;

import auracore.key49.core.model.enums.SriEnvironment;

/**
 * URLs de los servicios SOAP del SRI por ambiente.
 *
 * <p>Ambiente de pruebas (celcer) y producción (cel).
 */
public final class SriEndpoints {

    private static final String BASE_TEST = "https://celcer.sri.gob.ec/comprobantes-electronicos-ws";
    private static final String BASE_PROD = "https://cel.sri.gob.ec/comprobantes-electronicos-ws";

    private SriEndpoints() {
    }

    /**
     * URL del servicio SOAP de Recepción de Comprobantes.
     */
    public static String receptionUrl(SriEnvironment environment) {
        return baseUrl(environment) + "/RecepcionComprobantesOffline?wsdl";
    }

    /**
     * URL del servicio SOAP de Autorización de Comprobantes.
     */
    public static String authorizationUrl(SriEnvironment environment) {
        return baseUrl(environment) + "/AutorizacionComprobantesOffline?wsdl";
    }

    private static String baseUrl(SriEnvironment environment) {
        return switch (environment) {
            case TEST -> BASE_TEST;
            case PRODUCTION -> BASE_PROD;
        };
    }
}
