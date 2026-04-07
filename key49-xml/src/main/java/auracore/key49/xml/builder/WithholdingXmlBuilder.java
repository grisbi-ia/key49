package auracore.key49.xml.builder;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Genera XML de comprobante de retención conforme al XSD
 * ComprobanteRetencion v2.0.0.
 */
public final class WithholdingXmlBuilder {

    private static final String RETENTION_VERSION = "2.0.0";
    private static final String COD_DOC = "07";
    private static final DateTimeFormatter SRI_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private WithholdingXmlBuilder() {
    }

    public static String build(WithholdingData data) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            var docBuilder = factory.newDocumentBuilder();
            var doc = docBuilder.newDocument();
            doc.setXmlStandalone(true);

            var root = doc.createElement("comprobanteRetencion");
            root.setAttribute("id", "comprobante");
            root.setAttribute("version", RETENTION_VERSION);
            doc.appendChild(root);

            buildInfoTributaria(doc, root, data);
            buildInfoCompRetencion(doc, root, data);
            buildDocsSustento(doc, root, data);
            buildInfoAdicional(doc, root, data);

            return serialize(doc);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Failed to create XML document", e);
        }
    }

    private static void buildInfoTributaria(Document doc, Element root, WithholdingData data) {
        var info = doc.createElement("infoTributaria");
        root.appendChild(info);

        var tp = data.taxpayer();
        appendElement(doc, info, "ambiente", tp.environment());
        appendElement(doc, info, "tipoEmision", tp.emissionType());
        appendElement(doc, info, "razonSocial", tp.legalName());
        appendElementOptional(doc, info, "nombreComercial", tp.tradeName());
        appendElement(doc, info, "ruc", tp.ruc());
        appendElement(doc, info, "claveAcceso", data.accessKey());
        appendElement(doc, info, "codDoc", COD_DOC);
        appendElement(doc, info, "estab", data.establishment());
        appendElement(doc, info, "ptoEmi", data.issuePoint());
        appendElement(doc, info, "secuencial", data.sequenceNumber());
        appendElement(doc, info, "dirMatriz", tp.mainAddress());
        appendElementOptional(doc, info, "agenteRetencion", tp.withholdingAgent());
        appendElementOptional(doc, info, "contribuyenteRimpe", tp.rimpeContributor());
    }

    private static void buildInfoCompRetencion(Document doc, Element root, WithholdingData data) {
        var info = doc.createElement("infoCompRetencion");
        root.appendChild(info);

        appendElement(doc, info, "fechaEmision", data.issueDate().format(SRI_DATE_FORMAT));
        appendElementOptional(doc, info, "dirEstablecimiento", data.taxpayer().establishmentAddress());
        appendElementOptional(doc, info, "contribuyenteEspecial", data.taxpayer().specialTaxpayer());
        if (data.taxpayer().requiredAccounting()) {
            appendElement(doc, info, "obligadoContabilidad", "SI");
        }
        appendElement(doc, info, "tipoIdentificacionSujetoRetenido", data.subject().idType());
        appendElementOptional(doc, info, "tipoSujetoRetenido", data.subject().subjectType());
        appendElement(doc, info, "parteRel", data.relatedParty() ? "SI" : "NO");
        appendElement(doc, info, "razonSocialSujetoRetenido", data.subject().name());
        appendElement(doc, info, "identificacionSujetoRetenido", data.subject().id());
        appendElement(doc, info, "periodoFiscal", data.fiscalPeriod());
    }

    private static void buildDocsSustento(Document doc, Element root, WithholdingData data) {
        if (data.supportingDocuments() == null || data.supportingDocuments().isEmpty()) {
            return;
        }

        var docsSustento = doc.createElement("docsSustento");
        root.appendChild(docsSustento);

        for (var sd : data.supportingDocuments()) {
            var docSustento = doc.createElement("docSustento");
            docsSustento.appendChild(docSustento);

            appendElement(doc, docSustento, "codSustento", sd.supportCode());
            appendElement(doc, docSustento, "codDocSustento", sd.documentCode());
            appendElement(doc, docSustento, "numDocSustento", sd.documentNumber());
            appendElement(doc, docSustento, "fechaEmisionDocSustento",
                    sd.issueDate().format(SRI_DATE_FORMAT));
            if (sd.accountingDate() != null) {
                appendElement(doc, docSustento, "fechaRegistroContable",
                        sd.accountingDate().format(SRI_DATE_FORMAT));
            }
            appendElementOptional(doc, docSustento, "numAutDocSustento", sd.authorizationNumber());
            appendElement(doc, docSustento, "pagoLocExt", sd.paymentLocality());
            appendElementOptional(doc, docSustento, "tipoRegi", sd.regimeType());
            appendElementOptional(doc, docSustento, "paisEfecPago", sd.paymentCountry());
            appendElementOptional(doc, docSustento, "aplicConvDobTrib", sd.doubleTaxation());
            appendElementOptional(doc, docSustento, "pagExtSujRetNorLeg", sd.subjectToRetention());
            appendElementOptional(doc, docSustento, "pagoRegFis", sd.fiscalRegime());
            appendDecimal(doc, docSustento, "totalSinImpuestos", sd.totalWithoutTax());
            appendDecimal(doc, docSustento, "importeTotal", sd.totalAmount());

            buildImpuestosDocSustento(doc, docSustento, sd);
            buildRetenciones(doc, docSustento, sd);
            buildPagos(doc, docSustento, sd);
        }
    }

    private static void buildImpuestosDocSustento(Document doc, Element parent,
            WithholdingData.SupportingDocument sd) {
        if (sd.taxes() == null || sd.taxes().isEmpty()) {
            return;
        }

        var impuestos = doc.createElement("impuestosDocSustento");
        parent.appendChild(impuestos);

        for (var tax : sd.taxes()) {
            var impuesto = doc.createElement("impuestoDocSustento");
            impuestos.appendChild(impuesto);

            appendElement(doc, impuesto, "codImpuestoDocSustento", tax.taxCode());
            appendElement(doc, impuesto, "codigoPorcentaje", tax.rateCode());
            appendDecimal(doc, impuesto, "baseImponible", tax.taxableBase());
            appendDecimal(doc, impuesto, "tarifa", tax.rate());
            appendDecimal(doc, impuesto, "valorImpuesto", tax.amount());
        }
    }

    private static void buildRetenciones(Document doc, Element parent,
            WithholdingData.SupportingDocument sd) {
        if (sd.withholdings() == null || sd.withholdings().isEmpty()) {
            return;
        }

        var retenciones = doc.createElement("retenciones");
        parent.appendChild(retenciones);

        for (var wh : sd.withholdings()) {
            var retencion = doc.createElement("retencion");
            retenciones.appendChild(retencion);

            appendElement(doc, retencion, "codigo", wh.code());
            appendElement(doc, retencion, "codigoRetencion", wh.retentionCode());
            appendDecimal(doc, retencion, "baseImponible", wh.taxableBase());
            appendDecimal(doc, retencion, "porcentajeRetener", wh.retentionRate());
            appendDecimal(doc, retencion, "valorRetenido", wh.retainedAmount());
        }
    }

    private static void buildPagos(Document doc, Element parent,
            WithholdingData.SupportingDocument sd) {
        if (sd.payments() == null || sd.payments().isEmpty()) {
            return;
        }

        var pagos = doc.createElement("pagos");
        parent.appendChild(pagos);

        for (var payment : sd.payments()) {
            var pago = doc.createElement("pago");
            pagos.appendChild(pago);

            appendElement(doc, pago, "formaPago", payment.paymentMethod());
            appendDecimal(doc, pago, "total", payment.total());
        }
    }

    private static void buildInfoAdicional(Document doc, Element root, WithholdingData data) {
        if (data.additionalInfo() == null || data.additionalInfo().isEmpty()) {
            return;
        }

        var infoAdicional = doc.createElement("infoAdicional");
        root.appendChild(infoAdicional);

        for (Map.Entry<String, String> entry : data.additionalInfo().entrySet()) {
            var campo = doc.createElement("campoAdicional");
            campo.setAttribute("nombre", entry.getKey());
            campo.setTextContent(entry.getValue());
            infoAdicional.appendChild(campo);
        }
    }

    // ── Helpers ──

    private static void appendElement(Document doc, Element parent, String name, String value) {
        var el = doc.createElement(name);
        el.setTextContent(value != null ? value : "");
        parent.appendChild(el);
    }

    private static void appendElementOptional(Document doc, Element parent, String name, String value) {
        if (value != null && !value.isBlank()) {
            appendElement(doc, parent, name, value);
        }
    }

    private static void appendDecimal(Document doc, Element parent, String name, BigDecimal value) {
        appendElement(doc, parent, name, value != null ? String.format("%.2f", value) : "0.00");
    }

    private static String serialize(Document doc) {
        try {
            var transformerFactory = TransformerFactory.newInstance();
            var transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            var writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize XML", e);
        }
    }
}
