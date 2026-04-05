package auracore.key49.sri.parser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import auracore.key49.sri.SriException;
import auracore.key49.sri.model.AuthorizationStatus;
import auracore.key49.sri.model.SriAuthorizationResponse;
import auracore.key49.sri.model.SriMessage;

/**
 * Parser de la respuesta SOAP del servicio de Autorización del SRI.
 *
 * <p>Extrae el estado (AUTORIZADO/NO AUTORIZADO), número de autorización,
 * fecha de autorización, XML autorizado y mensajes de la respuesta SOAP.
 */
public final class SriAuthorizationResponseParser {

    private SriAuthorizationResponseParser() {
    }

    /**
     * Parsea el cuerpo de la respuesta SOAP de Autorización.
     *
     * @param soapResponse respuesta SOAP completa como String
     * @return respuesta parseada con estado, autorización y mensajes
     * @throws SriException si la respuesta no puede ser parseada
     */
    public static SriAuthorizationResponse parse(String soapResponse) {
        if (soapResponse == null || soapResponse.isBlank()) {
            throw new SriException("SRI authorization response is null or empty");
        }

        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            var document = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(soapResponse)));

            return parseDocument(document);
        } catch (SriException e) {
            throw e;
        } catch (Exception e) {
            throw new SriException("Failed to parse SRI authorization response", e);
        }
    }

    private static SriAuthorizationResponse parseDocument(Document document) {
        // Find the <autorizacion> element (first one)
        var autorizaciones = document.getElementsByTagName("autorizacion");
        if (autorizaciones.getLength() == 0) {
            throw new SriException("Missing 'autorizacion' element in SRI authorization response");
        }

        var autorizacion = (Element) autorizaciones.item(0);

        var estado = getChildText(autorizacion, "estado");
        if (estado == null || estado.isBlank()) {
            throw new SriException("Missing 'estado' in SRI authorization response");
        }

        AuthorizationStatus status;
        try {
            status = AuthorizationStatus.fromValue(estado);
        } catch (IllegalArgumentException e) {
            throw new SriException("Unknown SRI authorization status: " + estado, e);
        }

        var authorizationNumber = getChildText(autorizacion, "numeroAutorizacion");
        var authorizationDate = getChildText(autorizacion, "fechaAutorizacion");
        var authorizedXml = getChildText(autorizacion, "comprobante");
        var messages = parseMessages(autorizacion);

        return new SriAuthorizationResponse(
                status,
                authorizationNumber,
                authorizationDate,
                getElementText(document, "claveAccesoConsultada"),
                authorizedXml,
                messages
        );
    }

    private static List<SriMessage> parseMessages(Element autorizacion) {
        var messages = new ArrayList<SriMessage>();
        var messageNodes = autorizacion.getElementsByTagName("mensaje");

        for (int i = 0; i < messageNodes.getLength(); i++) {
            var node = messageNodes.item(i);
            if (node instanceof Element element
                    && node.getParentNode() instanceof Element parent
                    && "mensajes".equals(parent.getTagName())) {
                var identifier = getChildText(element, "identificador");
                var message = getChildText(element, "mensaje");
                var additionalInfo = getChildText(element, "informacionAdicional");
                var type = getChildText(element, "tipo");
                messages.add(new SriMessage(identifier, message, additionalInfo, type));
            }
        }

        return messages;
    }

    private static String getElementText(Document document, String tagName) {
        var nodes = document.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }

    private static String getChildText(Element parent, String childTag) {
        var nodes = parent.getElementsByTagName(childTag);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }
}
