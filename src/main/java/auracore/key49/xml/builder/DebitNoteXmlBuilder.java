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
 * Genera el XML de nota de débito electrónica conforme al XSD NotaDebito v1.0.0
 * del SRI.
 *
 * <p>
 * Los nombres de elementos XML respetan el XSD del SRI (en español):
 * infoTributaria, infoNotaDebito, motivos, infoAdicional.
 *
 * <p>
 * Diferencia clave con nota de crédito: usa {@code <motivos>} (lista de razón +
 * valor) en lugar de {@code <detalles>} (ítems).
 */

public final class DebitNoteXmlBuilder {

    /** Versión del XSD de nota de débito del SRI. */
    public static final String NOTA_DEBITO_VERSION = "1.0.0";

    /** Formato de fecha del SRI: dd/MM/yyyy. */
    private static final DateTimeFormatter SRI_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Código de documento para nota de débito. */
    private static final String COD_DOC_NOTA_DEBITO = "05";

    private DebitNoteXmlBuilder() {
    }

    /**
     * Genera el XML completo de una nota de débito electrónica.
     *
     * @param data datos de la nota de débito con toda la información necesaria
     * @return XML como String, con declaración XML y encoding UTF-8
     * @throws XmlBuildException si ocurre un error al construir el XML
     */
    public static String build(DebitNoteData data) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            var builder = factory.newDocumentBuilder();
            var doc = builder.newDocument();
            doc.setXmlStandalone(true);

            var notaDebito = doc.createElement("notaDebito");
            notaDebito.setAttribute("id", "comprobante");
            notaDebito.setAttribute("version", NOTA_DEBITO_VERSION);
            doc.appendChild(notaDebito);

            buildInfoTributaria(doc, notaDebito, data);
            buildInfoNotaDebito(doc, notaDebito, data);
            buildMotivos(doc, notaDebito, data);
            buildInfoAdicional(doc, notaDebito, data);

            return serialize(doc);
        } catch (ParserConfigurationException | TransformerException e) {
            throw new XmlBuildException("Failed to build debit note XML", e);
        }
    }

    private static void buildInfoTributaria(Document doc, Element parent, DebitNoteData data) {
        var info = doc.createElement("infoTributaria");
        parent.appendChild(info);

        var tp = data.taxpayer();

        appendElement(doc, info, "ambiente", tp.environment());
        appendElement(doc, info, "tipoEmision", tp.emissionType());
        appendElement(doc, info, "razonSocial", tp.legalName());
        appendOptionalElement(doc, info, "nombreComercial", tp.tradeName());
        appendElement(doc, info, "ruc", tp.ruc());
        appendElement(doc, info, "claveAcceso", data.accessKey());
        appendElement(doc, info, "codDoc", COD_DOC_NOTA_DEBITO);
        appendElement(doc, info, "estab", data.establishment());
        appendElement(doc, info, "ptoEmi", data.issuePoint());
        appendElement(doc, info, "secuencial", data.sequenceNumber());
        appendElement(doc, info, "dirMatriz", tp.mainAddress());
        appendOptionalElement(doc, info, "agenteRetencion", tp.withholdingAgent());
        appendOptionalElement(doc, info, "contribuyenteRimpe", tp.rimpeContributor());
    }

    private static void buildInfoNotaDebito(Document doc, Element parent, DebitNoteData data) {
        var info = doc.createElement("infoNotaDebito");
        parent.appendChild(info);

        var tp = data.taxpayer();
        var recipient = data.recipient();

        appendElement(doc, info, "fechaEmision", data.issueDate().format(SRI_DATE_FORMAT));
        appendOptionalElement(doc, info, "dirEstablecimiento", tp.establishmentAddress());
        appendElement(doc, info, "tipoIdentificacionComprador", recipient.idType());
        appendElement(doc, info, "razonSocialComprador", recipient.name());
        appendElement(doc, info, "identificacionComprador", recipient.id());
        appendOptionalElement(doc, info, "contribuyenteEspecial", tp.specialTaxpayer());

        if (tp.requiredAccounting()) {
            appendElement(doc, info, "obligadoContabilidad", "SI");
        } else {
            appendElement(doc, info, "obligadoContabilidad", "NO");
        }

        appendElement(doc, info, "codDocModificado", data.modifiedDocumentCode());
        appendElement(doc, info, "numDocModificado", data.modifiedDocumentNumber());
        appendElement(doc, info, "fechaEmisionDocSustento", data.modifiedDocumentDate().format(SRI_DATE_FORMAT));
        appendDecimalElement(doc, info, "totalSinImpuestos", data.totalWithoutTax());

        // impuestos
        buildImpuestos(doc, info, data);

        appendDecimalElement(doc, info, "valorTotal", data.totalValue());

        // pagos (optional)
        buildPagos(doc, info, data);
    }

    private static void buildImpuestos(Document doc, Element parent, DebitNoteData data) {
        var impuestos = doc.createElement("impuestos");
        parent.appendChild(impuestos);

        for (var tax : data.taxes()) {
            var impuesto = doc.createElement("impuesto");
            impuestos.appendChild(impuesto);

            appendElement(doc, impuesto, "codigo", tax.taxCode());
            appendElement(doc, impuesto, "codigoPorcentaje", tax.rateCode());
            appendDecimalElement(doc, impuesto, "tarifa", tax.rate());
            appendDecimalElement(doc, impuesto, "baseImponible", tax.taxableBase());
            appendDecimalElement(doc, impuesto, "valor", tax.amount());
        }
    }

    private static void buildPagos(Document doc, Element parent, DebitNoteData data) {
        if (data.payments() == null || data.payments().isEmpty()) {
            return;
        }

        var pagos = doc.createElement("pagos");
        parent.appendChild(pagos);

        for (var payment : data.payments()) {
            var pago = doc.createElement("pago");
            pagos.appendChild(pago);

            appendElement(doc, pago, "formaPago", payment.paymentMethod());
            appendDecimalElement(doc, pago, "total", payment.total());
            if (payment.term() != null) {
                appendElement(doc, pago, "plazo", String.valueOf(payment.term()));
            }
            if (payment.timeUnit() != null) {
                appendElement(doc, pago, "unidadTiempo", payment.timeUnit());
            }
        }
    }

    private static void buildMotivos(Document doc, Element parent, DebitNoteData data) {
        var motivos = doc.createElement("motivos");
        parent.appendChild(motivos);

        for (var reason : data.reasons()) {
            var motivo = doc.createElement("motivo");
            motivos.appendChild(motivo);

            appendElement(doc, motivo, "razon", reason.description());
            appendDecimalElement(doc, motivo, "valor", reason.amount());
        }
    }

    private static void buildInfoAdicional(Document doc, Element parent, DebitNoteData data) {
        if (data.additionalInfo() == null || data.additionalInfo().isEmpty()) {
            return;
        }

        var infoAdicional = doc.createElement("infoAdicional");
        parent.appendChild(infoAdicional);

        int count = 0;
        for (Map.Entry<String, String> entry : data.additionalInfo().entrySet()) {
            if (count >= 15) {
                break; // XSD permite máximo 15 campos adicionales
            }
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

    private static void appendDecimalElement(Document doc, Element parent, String name, BigDecimal value) {
        var element = doc.createElement(name);
        element.setTextContent(value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
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
