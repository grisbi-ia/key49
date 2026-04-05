package auracore.key49.sri.parser;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import auracore.key49.sri.SriException;
import auracore.key49.sri.model.ReceptionStatus;
import auracore.key49.sri.model.SriMessage;
import auracore.key49.sri.model.SriReceptionResponse;

/**
 * Parser de la respuesta SOAP del servicio de Recepción del SRI.
 *
 * <p>Extrae el estado (RECIBIDA/DEVUELTA), la clave de acceso y los mensajes
 * de la respuesta XML del SRI.
 */
public final class SriReceptionResponseParser {

    private SriReceptionResponseParser() {
    }

    /**
     * Parsea el cuerpo de la respuesta SOAP de Recepción.
     *
     * @param soapResponse respuesta SOAP completa como String
     * @return respuesta parseada con estado, clave de acceso y mensajes
     * @throws SriException si la respuesta no puede ser parseada
     */
    public static SriReceptionResponse parse(String soapResponse) {
        if (soapResponse == null || soapResponse.isBlank()) {
            throw new SriException("SRI reception response is null or empty");
        }

        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Disable external entities for security
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            var document = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(soapResponse)));

            return parseDocument(document);
        } catch (SriException e) {
            throw e;
        } catch (Exception e) {
            throw new SriException("Failed to parse SRI reception response", e);
        }
    }

    private static SriReceptionResponse parseDocument(Document document) {
        var estado = getElementText(document, "estado");
        if (estado == null || estado.isBlank()) {
            throw new SriException("Missing 'estado' in SRI response");
        }

        ReceptionStatus status;
        try {
            status = ReceptionStatus.fromValue(estado);
        } catch (IllegalArgumentException e) {
            throw new SriException("Unknown SRI reception status: " + estado, e);
        }

        var accessKey = getElementText(document, "claveAcceso");
        var messages = parseMessages(document);

        return new SriReceptionResponse(status, accessKey, messages);
    }

    private static List<SriMessage> parseMessages(Document document) {
        var messages = new ArrayList<SriMessage>();
        var messageNodes = document.getElementsByTagName("mensaje");

        for (int i = 0; i < messageNodes.getLength(); i++) {
            var node = messageNodes.item(i);
            // Only process <mensaje> elements that are direct children of <mensajes>
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
