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
 * Genera el XML de nota de crédito electrónica conforme al XSD NotaCredito
 * v1.1.0 del SRI.
 *
 * <p>
 * Los nombres de elementos XML respetan el XSD del SRI (en español):
 * infoTributaria, infoNotaCredito, detalles, infoAdicional.
 */

public final class CreditNoteXmlBuilder {

    /** Versión del XSD de nota de crédito del SRI. */
    public static final String NOTA_CREDITO_VERSION = "1.1.0";

    /** Formato de fecha del SRI: dd/MM/yyyy. */
    private static final DateTimeFormatter SRI_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Código de documento para nota de crédito. */
    private static final String COD_DOC_NOTA_CREDITO = "04";

    private CreditNoteXmlBuilder() {
    }

    /**
     * Genera el XML completo de una nota de crédito electrónica.
     *
     * @param data datos de la nota de crédito con toda la información necesaria
     * @return XML como String, con declaración XML y encoding UTF-8
     * @throws XmlBuildException si ocurre un error al construir el XML
     */
    public static String build(CreditNoteData data) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            var builder = factory.newDocumentBuilder();
            var doc = builder.newDocument();
            doc.setXmlStandalone(true);

            var notaCredito = doc.createElement("notaCredito");
            notaCredito.setAttribute("id", "comprobante");
            notaCredito.setAttribute("version", NOTA_CREDITO_VERSION);
            doc.appendChild(notaCredito);

            buildInfoTributaria(doc, notaCredito, data);
            buildInfoNotaCredito(doc, notaCredito, data);
            buildDetalles(doc, notaCredito, data);
            buildInfoAdicional(doc, notaCredito, data);

            return serialize(doc);
        } catch (ParserConfigurationException | TransformerException e) {
            throw new XmlBuildException("Failed to build credit note XML", e);
        }
    }

    private static void buildInfoTributaria(Document doc, Element parent, CreditNoteData data) {
        var info = doc.createElement("infoTributaria");
        parent.appendChild(info);

        var tp = data.taxpayer();

        appendElement(doc, info, "ambiente", tp.environment());
        appendElement(doc, info, "tipoEmision", tp.emissionType());
        appendElement(doc, info, "razonSocial", tp.legalName());
        appendOptionalElement(doc, info, "nombreComercial", tp.tradeName());
        appendElement(doc, info, "ruc", tp.ruc());
        appendElement(doc, info, "claveAcceso", data.accessKey());
        appendElement(doc, info, "codDoc", COD_DOC_NOTA_CREDITO);
        appendElement(doc, info, "estab", data.establishment());
        appendElement(doc, info, "ptoEmi", data.issuePoint());
        appendElement(doc, info, "secuencial", data.sequenceNumber());
        appendElement(doc, info, "dirMatriz", tp.mainAddress());
        appendOptionalElement(doc, info, "agenteRetencion", tp.withholdingAgent());
        appendOptionalElement(doc, info, "contribuyenteRimpe", tp.rimpeContributor());
    }

    private static void buildInfoNotaCredito(Document doc, Element parent, CreditNoteData data) {
        var info = doc.createElement("infoNotaCredito");
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
        appendDecimalElement(doc, info, "totalSinImpuestos", data.subtotalBeforeTax());
        appendDecimalElement(doc, info, "valorModificacion", data.modificationValue());
        appendOptionalElement(doc, info, "moneda", data.currency());

        // totalConImpuestos
        buildTotalConImpuestos(doc, info, data);

        appendElement(doc, info, "motivo", data.reason());
    }

    private static void buildTotalConImpuestos(Document doc, Element parent, CreditNoteData data) {
        var totalConImpuestos = doc.createElement("totalConImpuestos");
        parent.appendChild(totalConImpuestos);

        for (var totalTax : data.totalTaxes()) {
            var totalImpuesto = doc.createElement("totalImpuesto");
            totalConImpuestos.appendChild(totalImpuesto);

            appendElement(doc, totalImpuesto, "codigo", totalTax.taxCode());
            appendElement(doc, totalImpuesto, "codigoPorcentaje", totalTax.rateCode());
            appendDecimalElement(doc, totalImpuesto, "baseImponible", totalTax.taxableBase());
            appendDecimalElement(doc, totalImpuesto, "valor", totalTax.amount());
        }
    }

    private static void buildDetalles(Document doc, Element parent, CreditNoteData data) {
        var detalles = doc.createElement("detalles");
        parent.appendChild(detalles);

        for (var item : data.items()) {
            var detalle = doc.createElement("detalle");
            detalles.appendChild(detalle);

            appendOptionalElement(doc, detalle, "codigoInterno", item.internalCode());
            appendOptionalElement(doc, detalle, "codigoAdicional", item.additionalCode());
            appendElement(doc, detalle, "descripcion", item.description());
            appendSriDecimalElement(doc, detalle, "cantidad", item.quantity(), 6);
            appendSriDecimalElement(doc, detalle, "precioUnitario", item.unitPrice(), 6);
            appendDecimalElement(doc, detalle, "descuento", item.discount());
            appendDecimalElement(doc, detalle, "precioTotalSinImpuesto", item.subtotalBeforeTax());

            // impuestos del detalle
            var impuestos = doc.createElement("impuestos");
            detalle.appendChild(impuestos);

            for (var tax : item.taxes()) {
                var impuesto = doc.createElement("impuesto");
                impuestos.appendChild(impuesto);

                appendElement(doc, impuesto, "codigo", tax.taxCode());
                appendElement(doc, impuesto, "codigoPorcentaje", tax.rateCode());
                appendSriDecimalElement(doc, impuesto, "tarifa", tax.rate(), 2);
                appendDecimalElement(doc, impuesto, "baseImponible", tax.taxableBase());
                appendDecimalElement(doc, impuesto, "valor", tax.amount());
            }
        }
    }

    private static void buildInfoAdicional(Document doc, Element parent, CreditNoteData data) {
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
        appendSriDecimalElement(doc, parent, name, value, 2);
    }

    private static void appendSriDecimalElement(Document doc, Element parent, String name,
                                                 BigDecimal value, int scale) {
        var element = doc.createElement(name);
        element.setTextContent(value.setScale(scale, java.math.RoundingMode.HALF_UP).toPlainString());
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
