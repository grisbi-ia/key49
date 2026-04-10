package auracore.key49.api.service;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import auracore.key49.api.exception.BusinessException;
import auracore.key49.api.exception.DuplicateDocumentException;
import auracore.key49.admin.metrics.DocumentMetrics;
import auracore.key49.core.Key49Constants;
import auracore.key49.core.model.Document;
import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.core.model.enums.SriEnvironment;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.core.tenant.TenantContext;
import auracore.key49.xml.accesskey.AccessKeyGenerator;
import auracore.key49.xml.validation.XsdValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Servicio para procesamiento de documentos electrónicos enviados como XML raw.
 *
 * <p>
 * Valida el XML contra XSD, extrae metadatos (receptor, totales, serie), genera
 * la clave de acceso, inyecta la clave en el XML y persiste el documento.
 */
@ApplicationScoped
public class RawDocumentService {

    private static final DateTimeFormatter SRI_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Inject
    Logger log;

    @Inject
    TenantContext tenantContext;

    @Inject
    TenantConnectionManager tcm;

    @ConfigProperty(name = "key49.sri.environment", defaultValue = "test")
    String sriEnvironment;

    @Inject
    DocumentMetrics documentMetrics;

    /**
     * Procesa un XML raw: valida, extrae datos, genera clave de acceso,
     * persiste.
     */
    public Document createFromRawXml(String xml, String documentTypeCode,
            String idempotencyKey, String requestIp) {
        // 1. Validate document type header
        if (documentTypeCode == null || documentTypeCode.isBlank()) {
            throw new BusinessException("MISSING_DOCUMENT_TYPE",
                    "Header X-Document-Type is required", 400);
        }

        DocumentType documentType;
        try {
            documentType = DocumentType.fromSriCode(documentTypeCode);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("MISSING_DOCUMENT_TYPE",
                    "Invalid document type code: " + documentTypeCode, 400);
        }

        // 2. Parse XML
        org.w3c.dom.Document xmlDoc;
        try {
            var factory = DocumentBuilderFactory.newInstance();
            // Prevent XXE attacks
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var builder = factory.newDocumentBuilder();
            xmlDoc = builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new BusinessException("INVALID_XML_STRUCTURE",
                    "XML is malformed or not parseable: " + e.getMessage(), 400);
        }

        // 3. Validate codDoc matches X-Document-Type
        var codDoc = getElementText(xmlDoc, "codDoc");
        if (codDoc != null && !codDoc.equals(documentTypeCode)) {
            throw new BusinessException("DOCUMENT_TYPE_MISMATCH",
                    "X-Document-Type header '%s' does not match <codDoc> '%s' in XML"
                            .formatted(documentTypeCode, codDoc),
                    400);
        }

        // 4. Validate against XSD
        var xsdResult = XsdValidator.validate(xml, documentType);
        if (!xsdResult.valid()) {
            var errorMessages = xsdResult.errors().stream()
                    .map(Object::toString)
                    .toList();
            throw new BusinessException("XSD_VALIDATION_FAILED",
                    "XML does not conform to XSD: " + String.join("; ", errorMessages), 400);
        }

        // 5. Extract metadata from XML
        var metadata = extractMetadata(xmlDoc, documentType);

        // 6. Validate issue date is today
        var today = LocalDate.now(Key49Constants.EC_ZONE);
        if (!today.equals(metadata.issueDate())) {
            throw new BusinessException("VALIDATION_ERROR",
                    "Issue date must be today (%s) but was %s".formatted(today, metadata.issueDate()), 400);
        }

        // 7. Generate access key — RUC comes from XML's <ruc> element
        var ruc = getRequiredElement(xmlDoc, "ruc", "RUC");
        var environment = "production".equalsIgnoreCase(sriEnvironment)
                ? SriEnvironment.PRODUCTION : SriEnvironment.TEST;
        var accessKey = AccessKeyGenerator.generate(
                metadata.issueDate(), documentType, ruc,
                environment, metadata.establishment(), metadata.issuePoint(),
                metadata.sequenceNumber());

        // 8. Inject access key into XML
        var finalXml = injectAccessKey(xmlDoc, accessKey, documentTypeCode);

        // 9. Persist
        final boolean[] created = {false};
        var doc = tcm.withTenantTransaction(tenantContext.getSchemaName(), em -> {
            if (idempotencyKey != null) {
                Document existing = em.createQuery(
                        "FROM Document d WHERE d.idempotencyKey = :key", Document.class)
                        .setParameter("key", idempotencyKey)
                        .getResultStream().findFirst().orElse(null);
                if (existing != null) {
                    log.infof("Idempotent raw request found | docId=%s key=%s", existing.id, idempotencyKey);
                    return existing;
                }
            }
            created[0] = true;
            return checkUniquenessAndPersist(em, metadata, documentType, accessKey,
                    finalXml, idempotencyKey, requestIp);
        });
        if (created[0]) {
            documentMetrics.recordCreated(tenantContext.getSchemaName(), doc.documentType);
        }
        return doc;
    }

    /**
     * Consulta documento raw por ID.
     */
    public Document findById(UUID id) {
        Document doc = tcm.withTenantSession(tenantContext.getSchemaName(), em
                -> em.find(Document.class, id));
        if (doc == null) {
            throw new BusinessException("DOCUMENT_NOT_FOUND", "Document not found: " + id, 404);
        }
        return doc;
    }

    // ── Private helpers ──
    private Document checkUniquenessAndPersist(
            EntityManager em,
            XmlMetadata metadata, DocumentType documentType,
            String accessKey, String finalXml,
            String idempotencyKey, String requestIp) {

        Document existing = em.createQuery(
                "FROM Document d WHERE d.documentType = :dt AND d.establishment = :est "
                + "AND d.issuePoint = :ip AND d.sequenceNumber = :sn", Document.class)
                .setParameter("dt", documentType.sriCode())
                .setParameter("est", metadata.establishment())
                .setParameter("ip", metadata.issuePoint())
                .setParameter("sn", metadata.sequenceNumber())
                .getResultStream().findFirst().orElse(null);

        if (existing == null) {
            return persistDocument(em, metadata, documentType, accessKey,
                    finalXml, idempotencyKey, requestIp);
        }

        if (existing.status.isRetryableTerminal()) {
            return recycleDocument(em, existing, metadata, documentType,
                    accessKey, finalXml, idempotencyKey, requestIp);
        }

        var docNumber = "%s-%s-%s".formatted(metadata.establishment(), metadata.issuePoint(), metadata.sequenceNumber());
        throw new DuplicateDocumentException(
                "DUPLICATE_DOCUMENT",
                "Document %s already exists with status %s".formatted(docNumber, existing.status.name()),
                existing.id, existing.status.name(), existing.accessKey, existing.authorizationDate);
    }

    private Document recycleDocument(EntityManager em, Document doc,
            XmlMetadata metadata, DocumentType documentType,
            String accessKey, String finalXml,
            String idempotencyKey, String requestIp) {
        log.infof("Recycling failed document %s (was %s) for resubmission", doc.id, doc.status);

        doc.issueDate = metadata.issueDate();
        doc.accessKey = accessKey;
        doc.recipientIdType = metadata.recipientIdType();
        doc.recipientId = metadata.recipientId();
        doc.recipientName = metadata.recipientName();
        doc.recipientEmail = metadata.recipientEmail();
        doc.totalAmount = metadata.totalAmount();
        doc.subtotalBeforeTax = metadata.subtotalBeforeTax();
        doc.vatAmount = metadata.vatAmount();
        doc.originalXml = finalXml;
        doc.idempotencyKey = idempotencyKey;
        doc.requestIp = requestIp;

        doc.status = DocumentStatus.CREATED;
        doc.authorizationNumber = null;
        doc.authorizationDate = null;
        doc.sriSubmissionDate = null;
        doc.lastErrorCode = null;
        doc.lastErrorMessage = null;
        doc.sriMessages = null;
        doc.unsignedXmlPath = null;
        doc.signedXmlPath = null;
        doc.authorizedXmlPath = null;
        doc.ridePath = null;
        doc.retryCount = 0;
        doc.nextRetryAt = null;
        doc.updatedAt = Instant.now();

        em.merge(doc);
        var outbox = OutboxEvent.create(doc.id, "doc.sign", "{}");
        em.persist(outbox);
        em.flush();
        return doc;
    }

    private Document persistDocument(
            EntityManager em,
            XmlMetadata metadata, DocumentType documentType,
            String accessKey, String finalXml,
            String idempotencyKey, String requestIp) {

        var doc = new Document();
        doc.documentType = documentType.sriCode();
        doc.establishment = metadata.establishment();
        doc.issuePoint = metadata.issuePoint();
        doc.sequenceNumber = metadata.sequenceNumber();
        doc.issueDate = metadata.issueDate();
        doc.accessKey = accessKey;
        doc.recipientIdType = metadata.recipientIdType();
        doc.recipientId = metadata.recipientId();
        doc.recipientName = metadata.recipientName();
        doc.recipientEmail = metadata.recipientEmail();
        doc.totalAmount = metadata.totalAmount();
        doc.subtotalBeforeTax = metadata.subtotalBeforeTax();
        doc.vatAmount = metadata.vatAmount();
        doc.requestOrigin = "XML_RAW";
        doc.originalXml = finalXml;
        doc.idempotencyKey = idempotencyKey;
        doc.requestIp = requestIp;
        doc.status = DocumentStatus.CREATED;
        doc.createdAt = Instant.now();
        doc.updatedAt = Instant.now();

        em.persist(doc);
        var outbox = OutboxEvent.create(doc.id, "doc.sign", "{}");
        em.persist(outbox);
        em.flush();
        return doc;
    }

    /**
     * Extrae metadatos del XML necesarios para persistir en la tabla documents.
     */
    XmlMetadata extractMetadata(org.w3c.dom.Document xmlDoc, DocumentType documentType) {
        var establishment = getRequiredElement(xmlDoc, "estab", "establishment");
        var issuePoint = getRequiredElement(xmlDoc, "ptoEmi", "issue point");
        var sequenceNumber = getRequiredElement(xmlDoc, "secuencial", "sequence number");
        var issueDateStr = getRequiredElement(xmlDoc, "fechaEmision", "issue date");

        LocalDate issueDate;
        try {
            issueDate = LocalDate.parse(issueDateStr, SRI_DATE_FORMAT);
        } catch (Exception e) {
            throw new BusinessException("INVALID_XML_STRUCTURE",
                    "Invalid date format in <fechaEmision>: " + issueDateStr, 400);
        }

        // Extract recipient info based on document type
        String recipientIdType;
        String recipientId;
        String recipientName;
        String recipientEmail = null;

        if (documentType == DocumentType.WITHHOLDING) {
            recipientIdType = getElementText(xmlDoc, "tipoIdentificacionSujetoRetenido");
            recipientId = getElementText(xmlDoc, "identificacionSujetoRetenido");
            recipientName = getElementText(xmlDoc, "razonSocialSujetoRetenido");
        } else if (documentType == DocumentType.WAYBILL) {
            recipientIdType = getElementText(xmlDoc, "tipoIdentificacionDestinatario");
            recipientId = getElementText(xmlDoc, "identificacionDestinatario");
            recipientName = getElementText(xmlDoc, "razonSocialDestinatario");
        } else if (documentType == DocumentType.PURCHASE_CLEARANCE) {
            recipientIdType = getElementText(xmlDoc, "tipoIdentificacionProveedor");
            recipientId = getElementText(xmlDoc, "identificacionProveedor");
            recipientName = getElementText(xmlDoc, "razonSocialProveedor");
        } else {
            recipientIdType = getElementText(xmlDoc, "tipoIdentificacionComprador");
            recipientId = getElementText(xmlDoc, "identificacionComprador");
            recipientName = getElementText(xmlDoc, "razonSocialComprador");
        }

        if (recipientId == null || recipientId.isBlank()) {
            recipientId = "9999999999999";
            recipientName = recipientName != null ? recipientName : "CONSUMIDOR FINAL";
            recipientIdType = recipientIdType != null ? recipientIdType : "07";
        }
        if (recipientName == null || recipientName.isBlank()) {
            recipientName = "CONSUMIDOR FINAL";
        }
        if (recipientIdType == null || recipientIdType.isBlank()) {
            recipientIdType = "07";
        }

        // Extract totals
        var totalAmount = getDecimalElement(xmlDoc, "importeTotal", BigDecimal.ZERO);
        var subtotalBeforeTax = getDecimalElement(xmlDoc, "totalSinImpuestos", BigDecimal.ZERO);

        // Calculate VAT amount from totalConImpuestos
        var vatAmount = extractVatAmount(xmlDoc);

        // Try to extract email from infoAdicional
        recipientEmail = extractAdditionalInfoValue(xmlDoc, "email", "correo", "Email", "Correo");

        return new XmlMetadata(establishment, issuePoint, sequenceNumber, issueDate,
                recipientIdType, recipientId, recipientName, recipientEmail,
                totalAmount, subtotalBeforeTax, vatAmount);
    }

    /**
     * Inyecta la clave de acceso y codDoc en el XML.
     */
    String injectAccessKey(org.w3c.dom.Document xmlDoc, String accessKey, String codDoc) {
        var infoTributariaList = xmlDoc.getElementsByTagName("infoTributaria");
        if (infoTributariaList.getLength() == 0) {
            throw new BusinessException("INVALID_XML_STRUCTURE",
                    "Missing <infoTributaria> element in XML", 400);
        }

        var infoTributaria = (Element) infoTributariaList.item(0);

        // Replace or insert claveAcceso
        setOrCreateElement(xmlDoc, infoTributaria, "claveAcceso", accessKey, "ruc");

        // Ensure codDoc matches
        setOrCreateElement(xmlDoc, infoTributaria, "codDoc", codDoc, "claveAcceso");

        return serializeXml(xmlDoc);
    }

    /**
     * Establece el texto de un elemento existente o lo crea después del
     * elemento de referencia.
     */
    private void setOrCreateElement(org.w3c.dom.Document xmlDoc, Element parent,
            String tagName, String value, String afterTag) {
        var existing = parent.getElementsByTagName(tagName);
        if (existing.getLength() > 0) {
            existing.item(0).setTextContent(value);
        } else {
            var newElement = xmlDoc.createElement(tagName);
            newElement.setTextContent(value);
            var afterElements = parent.getElementsByTagName(afterTag);
            if (afterElements.getLength() > 0) {
                var afterNode = afterElements.item(0);
                parent.insertBefore(newElement, afterNode.getNextSibling());
            } else {
                parent.appendChild(newElement);
            }
        }
    }

    private BigDecimal extractVatAmount(org.w3c.dom.Document xmlDoc) {
        var totalConImpuestos = xmlDoc.getElementsByTagName("totalImpuesto");
        var vatTotal = BigDecimal.ZERO;
        for (int i = 0; i < totalConImpuestos.getLength(); i++) {
            var node = (Element) totalConImpuestos.item(i);
            var codigo = getChildText(node, "codigo");
            if ("2".equals(codigo)) { // IVA
                var valor = getChildText(node, "valor");
                if (valor != null) {
                    try {
                        vatTotal = vatTotal.add(new BigDecimal(valor));
                    } catch (NumberFormatException ignored) {
                        // skip invalid amounts
                    }
                }
            }
        }
        return vatTotal;
    }

    private String extractAdditionalInfoValue(org.w3c.dom.Document xmlDoc, String... names) {
        var infoAdicional = xmlDoc.getElementsByTagName("campoAdicional");
        for (int i = 0; i < infoAdicional.getLength(); i++) {
            var node = (Element) infoAdicional.item(i);
            var attrName = node.getAttribute("nombre");
            for (var name : names) {
                if (name.equalsIgnoreCase(attrName)) {
                    return node.getTextContent();
                }
            }
        }
        return null;
    }

    private String getElementText(org.w3c.dom.Document doc, String tagName) {
        var nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }

    private String getRequiredElement(org.w3c.dom.Document doc, String tagName, String fieldName) {
        var value = getElementText(doc, tagName);
        if (value == null || value.isBlank()) {
            throw new BusinessException("INVALID_XML_STRUCTURE",
                    "Missing required element <%s> (%s)".formatted(tagName, fieldName), 400);
        }
        return value;
    }

    private BigDecimal getDecimalElement(org.w3c.dom.Document doc, String tagName, BigDecimal defaultValue) {
        var text = getElementText(doc, tagName);
        if (text == null || text.isBlank()) {
            return defaultValue;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String getChildText(Element parent, String tagName) {
        var nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() > 0) {
            return nodes.item(0).getTextContent().trim();
        }
        return null;
    }

    private String serializeXml(org.w3c.dom.Document doc) {
        try {
            var transformer = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "no");
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "no");
            var writer = new StringWriter();
            transformer.transform(new javax.xml.transform.dom.DOMSource(doc),
                    new javax.xml.transform.stream.StreamResult(writer));
            return writer.toString();
        } catch (Exception e) {
            throw new BusinessException("INVALID_XML_STRUCTURE",
                    "Failed to serialize XML after access key injection", 400);
        }
    }

    /**
     * Metadatos extraídos del XML para persistir en la tabla documents.
     */
    record XmlMetadata(
            String establishment,
            String issuePoint,
            String sequenceNumber,
            LocalDate issueDate,
            String recipientIdType,
            String recipientId,
            String recipientName,
            String recipientEmail,
            BigDecimal totalAmount,
            BigDecimal subtotalBeforeTax,
            BigDecimal vatAmount) {

    }
}
