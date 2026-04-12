package auracore.key49.queue.consumer;

import java.time.Instant;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.InvalidStateTransitionException;
import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.core.model.enums.SriEnvironment;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.service.QuotaService;
import auracore.key49.core.tenant.MdcContext;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.queue.mapper.CreditNoteDataMapper;
import auracore.key49.queue.mapper.DebitNoteDataMapper;
import auracore.key49.queue.mapper.InvoiceDataMapper;
import auracore.key49.queue.mapper.PurchaseClearanceDataMapper;
import auracore.key49.queue.mapper.WaybillDataMapper;
import auracore.key49.queue.mapper.WithholdingDataMapper;
import auracore.key49.signer.CertificateCacheService;
import auracore.key49.signer.XAdESBESSigner;
import auracore.key49.xml.accesskey.AccessKeyGenerator;
import auracore.key49.xml.builder.CreditNoteXmlBuilder;
import auracore.key49.xml.builder.DebitNoteXmlBuilder;
import auracore.key49.xml.builder.InvoiceXmlBuilder;
import auracore.key49.xml.builder.PurchaseClearanceXmlBuilder;
import auracore.key49.xml.builder.WaybillXmlBuilder;
import auracore.key49.xml.builder.WithholdingXmlBuilder;
import io.smallrye.common.annotation.Blocking;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * Consumidor que firma documentos: genera XML, clave de acceso y firma
 * XAdES-BES. Transición: CREATED → SIGNED (éxito) o CREATED → FAILED (error
 * irrecuperable).
 */
@ApplicationScoped
public class SignConsumer {

    @Inject
    Logger log;

    @Inject
    ConsumerErrorHandler errorHandler;

    @Inject
    TenantRepository tenantRepository;

    @Inject
    TenantConnectionManager connectionManager;

    @Inject
    InvoiceDataMapper invoiceMapper;

    @Inject
    CreditNoteDataMapper creditNoteMapper;

    @Inject
    DebitNoteDataMapper debitNoteMapper;

    @Inject
    WaybillDataMapper waybillMapper;

    @Inject
    WithholdingDataMapper withholdingMapper;

    @Inject
    PurchaseClearanceDataMapper purchaseClearanceMapper;

    @Inject
    CertificateCacheService certificateCacheService;

    @Inject
    QuotaService quotaService;

    @Inject
    InFlightTracker tracker;

    @Incoming("doc-sign-in")
    @Blocking
    @ActivateRequestContext
    public void process(JsonObject json) {
        tracker.increment("SignConsumer");
        try {
            var event = DocumentEvent.fromJson(json);
            MdcContext.setTenant(event.tenantSchemaName());
            MdcContext.setDocument(event.documentId());
            log.infof("SignConsumer: processing documentId=%s, tenant=%s",
                    event.documentId(), event.tenantSchemaName());

            try {
                var tenant = tenantRepository.findBySchemaName(event.tenantSchemaName());
                if (tenant == null) {
                    log.errorf("SignConsumer: tenant not found: %s", event.tenantSchemaName());
                    return;
                }

                connectionManager.withTenantTransaction(event.tenantSchemaName(), em -> {
                    var doc = em.find(Document.class, event.documentId());
                    if (doc == null) {
                        log.warnf("SignConsumer: document not found: %s", event.documentId());
                        return null;
                    }
                    signDocument(doc, tenant, em);
                    return null;
                });
            } catch (Exception ex) {
                errorHandler.persistError(event.documentId(), event.tenantSchemaName(),
                        "SignConsumer", ex);
            }
        } finally {
            MdcContext.clear();
            tracker.decrement("SignConsumer");
        }
    }

    private void signDocument(Document doc, Tenant tenant, EntityManager em) {
        if (!doc.status.canTransitionTo(DocumentStatus.SIGNED)) {
            log.warnf("SignConsumer: skip document %s in state %s", doc.id, doc.status);
            return;
        }

        try {
            var docType = DocumentType.fromSriCode(doc.documentType);
            var sriEnv = resolveEnvironment(tenant.environment);

            var accessKey = AccessKeyGenerator.generate(
                    doc.issueDate, docType, tenant.ruc, sriEnv,
                    doc.establishment, doc.issuePoint, doc.sequenceNumber);

            var unsignedXml = buildXml(docType, doc, tenant, accessKey);

            var certData = certificateCacheService.getOrLoad(
                    tenant.id, tenant.certificateP12, tenant.certificatePasswordEnc);
            var signedXml = XAdESBESSigner.sign(unsignedXml, certData);

            doc.transitionTo(DocumentStatus.SIGNED);
            doc.accessKey = accessKey;
            doc.originalXml = signedXml;
            doc.updatedAt = Instant.now();

            var outboxEvent = OutboxEvent.create(doc.id, "doc.send", "{}");
            em.persist(outboxEvent);

            log.infof("SignConsumer: signed document %s, accessKey=%s", doc.id, accessKey);

        } catch (InvalidStateTransitionException e) {
            log.warnf("SignConsumer: invalid state transition for document %s: %s", doc.id, e.getMessage());

        } catch (Exception e) {
            log.errorf(e, "SignConsumer: error signing document %s", doc.id);
            try {
                doc.transitionTo(DocumentStatus.FAILED);
                quotaService.releaseQuota(em, tenant.schemaName);
            } catch (InvalidStateTransitionException iste) {
                log.warnf("SignConsumer: cannot transition to FAILED from %s for document %s",
                        doc.status, doc.id);
            }
            doc.lastErrorMessage = e.getMessage();
            doc.updatedAt = Instant.now();
        }
    }

    static SriEnvironment resolveEnvironment(String tenantEnv) {
        return "production".equals(tenantEnv) ? SriEnvironment.PRODUCTION : SriEnvironment.TEST;
    }

    private String buildXml(DocumentType docType, Document doc, Tenant tenant, String accessKey) {
        return switch (docType) {
            case INVOICE -> {
                var data = invoiceMapper.build(doc, tenant, accessKey);
                yield InvoiceXmlBuilder.build(data);
            }
            case CREDIT_NOTE -> {
                var data = creditNoteMapper.build(doc, tenant, accessKey);
                yield CreditNoteXmlBuilder.build(data);
            }
            case DEBIT_NOTE -> {
                var data = debitNoteMapper.build(doc, tenant, accessKey);
                yield DebitNoteXmlBuilder.build(data);
            }
            case WITHHOLDING -> {
                var data = withholdingMapper.build(doc, tenant, accessKey);
                yield WithholdingXmlBuilder.build(data);
            }
            case WAYBILL -> {
                var data = waybillMapper.build(doc, tenant, accessKey);
                yield WaybillXmlBuilder.build(data);
            }
            case PURCHASE_CLEARANCE -> {
                var data = purchaseClearanceMapper.build(doc, tenant, accessKey);
                yield PurchaseClearanceXmlBuilder.build(data);
            }
            default ->
                throw new UnsupportedOperationException(
                        "Document type not supported for signing: " + docType);
        };
    }
}
