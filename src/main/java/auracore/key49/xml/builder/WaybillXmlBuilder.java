package auracore.key49.xml.builder;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Genera el XML de guía de remisión electrónica conforme al XSD GuiaRemision
 * v1.1.0 del SRI.
 *
 * <p>
 * Los nombres de elementos XML respetan el XSD del SRI (en español):
 * infoTributaria, infoGuiaRemision, destinatarios, infoAdicional.
 *
 * <p>
 * Diferencia clave con otros comprobantes: la guía de remisión NO tiene
 * impuestos, pagos ni totales. Es un documento de transporte con datos del
 * transportista, destinatarios e ítems transportados.
 */

public final class WaybillXmlBuilder {

    /** Versión del XSD de guía de remisión del SRI. */
    public static final String GUIA_REMISION_VERSION = "1.1.0";

    /** Formato de fecha del SRI: dd/MM/yyyy. */
    private static final DateTimeFormatter SRI_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Código de documento para guía de remisión. */
    private static final String COD_DOC_GUIA_REMISION = "06";

    private WaybillXmlBuilder() {
    }

    /**
     * Genera el XML completo de una guía de remisión electrónica.
     *
     * @param data datos de la guía de remisión con toda la información necesaria
     * @return XML como String, con declaración XML y encoding UTF-8
     * @throws XmlBuildException si ocurre un error al construir el XML
     */
    public static String build(WaybillData data) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            var builder = factory.newDocumentBuilder();
            var doc = builder.newDocument();
            doc.setXmlStandalone(true);

            var guiaRemision = doc.createElement("guiaRemision");
            guiaRemision.setAttribute("id", "comprobante");
            guiaRemision.setAttribute("version", GUIA_REMISION_VERSION);
            doc.appendChild(guiaRemision);

            buildInfoTributaria(doc, guiaRemision, data);
            buildInfoGuiaRemision(doc, guiaRemision, data);
            buildDestinatarios(doc, guiaRemision, data);
            buildInfoAdicional(doc, guiaRemision, data);

            return serialize(doc);
        } catch (ParserConfigurationException | TransformerException e) {
            throw new XmlBuildException("Failed to build waybill XML", e);
        }
    }

    private static void buildInfoTributaria(Document doc, Element parent, WaybillData data) {
        var info = doc.createElement("infoTributaria");
        parent.appendChild(info);

        var tp = data.taxpayer();

        appendElement(doc, info, "ambiente", tp.environment());
        appendElement(doc, info, "tipoEmision", tp.emissionType());
        appendElement(doc, info, "razonSocial", tp.legalName());
        appendOptionalElement(doc, info, "nombreComercial", tp.tradeName());
        appendElement(doc, info, "ruc", tp.ruc());
        appendElement(doc, info, "claveAcceso", data.accessKey());
        appendElement(doc, info, "codDoc", COD_DOC_GUIA_REMISION);
        appendElement(doc, info, "estab", data.establishment());
        appendElement(doc, info, "ptoEmi", data.issuePoint());
        appendElement(doc, info, "secuencial", data.sequenceNumber());
        appendElement(doc, info, "dirMatriz", tp.mainAddress());
        appendOptionalElement(doc, info, "agenteRetencion", tp.withholdingAgent());
        appendOptionalElement(doc, info, "contribuyenteRimpe", tp.rimpeContributor());
    }

    private static void buildInfoGuiaRemision(Document doc, Element parent, WaybillData data) {
        var info = doc.createElement("infoGuiaRemision");
        parent.appendChild(info);

        var tp = data.taxpayer();
        var carrier = data.carrier();

        appendOptionalElement(doc, info, "dirEstablecimiento", tp.establishmentAddress());
        appendElement(doc, info, "dirPartida", data.departureAddress());
        appendElement(doc, info, "razonSocialTransportista", carrier.name());
        appendElement(doc, info, "tipoIdentificacionTransportista", carrier.idType());
        appendElement(doc, info, "rucTransportista", carrier.id());
        appendOptionalElement(doc, info, "rise", carrier.rise());

        if (tp.requiredAccounting()) {
            appendElement(doc, info, "obligadoContabilidad", "SI");
        }
        appendOptionalElement(doc, info, "contribuyenteEspecial", tp.specialTaxpayer());

        appendElement(doc, info, "fechaIniTransporte", data.transportStartDate().format(SRI_DATE_FORMAT));
        appendElement(doc, info, "fechaFinTransporte", data.transportEndDate().format(SRI_DATE_FORMAT));
        appendElement(doc, info, "placa", data.licensePlate());
    }

    private static void buildDestinatarios(Document doc, Element parent, WaybillData data) {
        var destinatarios = doc.createElement("destinatarios");
        parent.appendChild(destinatarios);

        for (var addressee : data.addressees()) {
            buildDestinatario(doc, destinatarios, addressee);
        }
    }

    private static void buildDestinatario(Document doc, Element parent, WaybillData.Addressee addressee) {
        var destinatario = doc.createElement("destinatario");
        parent.appendChild(destinatario);

        appendElement(doc, destinatario, "identificacionDestinatario", addressee.id());
        appendElement(doc, destinatario, "razonSocialDestinatario", addressee.name());
        appendElement(doc, destinatario, "dirDestinatario", addressee.address());
        appendElement(doc, destinatario, "motivoTraslado", addressee.transferReason());
        appendOptionalElement(doc, destinatario, "docAduaneroUnico", addressee.customsDocument());
        appendOptionalElement(doc, destinatario, "codEstabDestino", addressee.destinationEstablishment());
        appendOptionalElement(doc, destinatario, "ruta", addressee.route());
        appendOptionalElement(doc, destinatario, "codDocSustento", addressee.supportDocumentCode());
        appendOptionalElement(doc, destinatario, "numDocSustento", addressee.supportDocumentNumber());
        appendOptionalElement(doc, destinatario, "numAutDocSustento", addressee.supportDocumentAuthNumber());
        if (addressee.supportDocumentIssueDate() != null) {
            appendElement(doc, destinatario, "fechaEmisionDocSustento",
                    addressee.supportDocumentIssueDate().format(SRI_DATE_FORMAT));
        }

        buildDetalles(doc, destinatario, addressee);
    }

    private static void buildDetalles(Document doc, Element parent, WaybillData.Addressee addressee) {
        var detalles = doc.createElement("detalles");
        parent.appendChild(detalles);

        for (var item : addressee.items()) {
            var detalle = doc.createElement("detalle");
            detalles.appendChild(detalle);

            appendOptionalElement(doc, detalle, "codigoInterno", item.mainCode());
            appendOptionalElement(doc, detalle, "codigoAdicional", item.auxiliaryCode());
            appendElement(doc, detalle, "descripcion", item.description());
            appendDecimalElement(doc, detalle, "cantidad", item.quantity(), 6);

            if (item.additionalDetails() != null && !item.additionalDetails().isEmpty()) {
                var detallesAdicionales = doc.createElement("detallesAdicionales");
                detalle.appendChild(detallesAdicionales);

                int count = 0;
                for (var detail : item.additionalDetails()) {
                    if (count >= 3) break; // XSD max 3
                    var detAdicional = doc.createElement("detAdicional");
                    detAdicional.setAttribute("nombre", detail.name());
                    detAdicional.setAttribute("valor", detail.value());
                    detallesAdicionales.appendChild(detAdicional);
                    count++;
                }
            }
        }
    }

    private static void buildInfoAdicional(Document doc, Element parent, WaybillData data) {
        if (data.additionalInfo() == null || data.additionalInfo().isEmpty()) {
            return;
        }

        var infoAdicional = doc.createElement("infoAdicional");
        parent.appendChild(infoAdicional);

        int count = 0;
        for (Map.Entry<String, String> entry : data.additionalInfo().entrySet()) {
            if (count >= 15) break; // XSD max 15
            var campo = doc.createElement("campoAdicional");
            campo.setAttribute("nombre", entry.getKey());
            campo.setTextContent(entry.getValue());
            infoAdicional.appendChild(campo);
            count++;
        }
    }

    // ── Helpers ──

    private static void appendElement(Document doc, Element parent, String name, String value) {
        var element = doc.createElement(name);
        element.setTextContent(value);
        parent.appendChild(element);
    }

    private static void appendOptionalElement(Document doc, Element parent, String name, String value) {
        if (value != null && !value.isBlank()) {
            appendElement(doc, parent, name, value);
        }
    }

    private static void appendDecimalElement(Document doc, Element parent, String name,
                                              BigDecimal value, int scale) {
        var element = doc.createElement(name);
        element.setTextContent(value.setScale(scale, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
        parent.appendChild(element);
    }

    private static String serialize(Document doc) throws TransformerException {
        var tf = TransformerFactory.newInstance();
        var transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        var writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }
}
