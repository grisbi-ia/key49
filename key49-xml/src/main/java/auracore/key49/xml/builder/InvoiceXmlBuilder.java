package auracore.key49.xml.builder;

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

import java.io.StringWriter;

/**
 * Genera el XML de factura electrónica conforme al XSD factura v2.1.0 del SRI.
 *
 * <p>Los nombres de elementos XML respetan el XSD del SRI (en español):
 * infoTributaria, infoFactura, detalles, pagos, infoAdicional.
 */
public final class InvoiceXmlBuilder {

    /** Versión del XSD de factura del SRI. */
    public static final String FACTURA_VERSION = "2.1.0";

    /** Formato de fecha del SRI: dd/MM/yyyy. */
    private static final DateTimeFormatter SRI_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Código de documento para factura. */
    private static final String COD_DOC_FACTURA = "01";

    private InvoiceXmlBuilder() {
    }

    /**
     * Genera el XML completo de una factura electrónica.
     *
     * @param data datos de la factura con toda la información necesaria
     * @return XML como String, con declaración XML y encoding UTF-8
     * @throws XmlBuildException si ocurre un error al construir el XML
     */
    public static String build(InvoiceData data) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            var builder = factory.newDocumentBuilder();
            var doc = builder.newDocument();
            doc.setXmlStandalone(true);

            var factura = doc.createElement("factura");
            factura.setAttribute("id", "comprobante");
            factura.setAttribute("version", FACTURA_VERSION);
            doc.appendChild(factura);

            buildInfoTributaria(doc, factura, data);
            buildInfoFactura(doc, factura, data);
            buildDetalles(doc, factura, data);
            buildInfoAdicional(doc, factura, data);

            return serialize(doc);
        } catch (ParserConfigurationException | TransformerException e) {
            throw new XmlBuildException("Failed to build invoice XML", e);
        }
    }

    private static void buildInfoTributaria(Document doc, Element parent, InvoiceData data) {
        var info = doc.createElement("infoTributaria");
        parent.appendChild(info);

        var tp = data.taxpayer();

        appendElement(doc, info, "ambiente", tp.environment());
        appendElement(doc, info, "tipoEmision", tp.emissionType());
        appendElement(doc, info, "razonSocial", tp.legalName());
        appendOptionalElement(doc, info, "nombreComercial", tp.tradeName());
        appendElement(doc, info, "ruc", tp.ruc());
        appendElement(doc, info, "claveAcceso", data.accessKey());
        appendElement(doc, info, "codDoc", COD_DOC_FACTURA);
        appendElement(doc, info, "estab", data.establishment());
        appendElement(doc, info, "ptoEmi", data.issuePoint());
        appendElement(doc, info, "secuencial", data.sequenceNumber());
        appendElement(doc, info, "dirMatriz", tp.mainAddress());
        appendOptionalElement(doc, info, "agenteRetencion", tp.withholdingAgent());
        appendOptionalElement(doc, info, "contribuyenteRimpe", tp.rimpeContributor());
    }

    private static void buildInfoFactura(Document doc, Element parent, InvoiceData data) {
        var info = doc.createElement("infoFactura");
        parent.appendChild(info);

        var tp = data.taxpayer();
        var recipient = data.recipient();

        appendElement(doc, info, "fechaEmision", data.issueDate().format(SRI_DATE_FORMAT));
        appendOptionalElement(doc, info, "dirEstablecimiento", tp.establishmentAddress());
        appendOptionalElement(doc, info, "contribuyenteEspecial", tp.specialTaxpayer());

        if (tp.requiredAccounting()) {
            appendElement(doc, info, "obligadoContabilidad", "SI");
        } else {
            appendElement(doc, info, "obligadoContabilidad", "NO");
        }

        appendElement(doc, info, "tipoIdentificacionComprador", recipient.idType());
        appendElement(doc, info, "razonSocialComprador", recipient.name());
        appendElement(doc, info, "identificacionComprador", recipient.id());
        appendOptionalElement(doc, info, "direccionComprador", recipient.address());
        appendDecimalElement(doc, info, "totalSinImpuestos", data.subtotalBeforeTax());
        appendDecimalElement(doc, info, "totalDescuento", data.totalDiscount());

        // totalConImpuestos
        buildTotalConImpuestos(doc, info, data);

        appendOptionalDecimalElement(doc, info, "propina", data.tip());
        appendDecimalElement(doc, info, "importeTotal", data.totalAmount());
        appendOptionalElement(doc, info, "moneda", data.currency());

        // pagos
        if (data.payments() != null && !data.payments().isEmpty()) {
            buildPagos(doc, info, data);
        }
    }

    private static void buildTotalConImpuestos(Document doc, Element parent, InvoiceData data) {
        var totalConImpuestos = doc.createElement("totalConImpuestos");
        parent.appendChild(totalConImpuestos);

        for (var totalTax : data.totalTaxes()) {
            var totalImpuesto = doc.createElement("totalImpuesto");
            totalConImpuestos.appendChild(totalImpuesto);

            appendElement(doc, totalImpuesto, "codigo", totalTax.taxCode());
            appendElement(doc, totalImpuesto, "codigoPorcentaje", totalTax.rateCode());
            appendOptionalDecimalElement(doc, totalImpuesto, "descuentoAdicional", totalTax.discountAdditional());
            appendDecimalElement(doc, totalImpuesto, "baseImponible", totalTax.taxableBase());
            appendOptionalDecimalElement(doc, totalImpuesto, "tarifa", totalTax.rate());
            appendDecimalElement(doc, totalImpuesto, "valor", totalTax.amount());
        }
    }

    private static void buildPagos(Document doc, Element parent, InvoiceData data) {
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
            appendOptionalElement(doc, pago, "unidadTiempo", payment.timeUnit());
        }
    }

    private static void buildDetalles(Document doc, Element parent, InvoiceData data) {
        var detalles = doc.createElement("detalles");
        parent.appendChild(detalles);

        for (var item : data.items()) {
            var detalle = doc.createElement("detalle");
            detalles.appendChild(detalle);

            appendOptionalElement(doc, detalle, "codigoPrincipal", item.mainCode());
            appendOptionalElement(doc, detalle, "codigoAuxiliar", item.auxiliaryCode());
            appendElement(doc, detalle, "descripcion", item.description());
            appendOptionalElement(doc, detalle, "unidadMedida", item.unitOfMeasure());
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

    private static void buildInfoAdicional(Document doc, Element parent, InvoiceData data) {
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

    private static void appendOptionalDecimalElement(Document doc, Element parent, String name, BigDecimal value) {
        if (value != null && value.compareTo(BigDecimal.ZERO) != 0) {
            appendDecimalElement(doc, parent, name, value);
        }
    }

    /**
     * Formatea un BigDecimal con la cantidad de decimales requerida por el SRI.
     * Montos usan 2 decimales, cantidades y precios unitarios usan 6.
     */
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
