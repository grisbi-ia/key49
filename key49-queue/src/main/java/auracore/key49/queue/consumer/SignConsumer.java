package auracore.key49.queue.consumer;

import java.time.Instant;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.hibernate.reactive.mutiny.Mutiny;
import org.jboss.logging.Logger;

import auracore.key49.core.model.Document;
import auracore.key49.core.model.InvalidStateTransitionException;
import auracore.key49.core.model.OutboxEvent;
import auracore.key49.core.model.Tenant;
import auracore.key49.core.model.enums.DocumentStatus;
import auracore.key49.core.model.enums.DocumentType;
import auracore.key49.core.model.enums.SriEnvironment;
import auracore.key49.core.repository.TenantRepository;
import auracore.key49.core.tenant.TenantConnectionManager;
import auracore.key49.queue.event.DocumentEvent;
import auracore.key49.queue.mapper.CreditNoteDataMapper;
import auracore.key49.queue.mapper.DebitNoteDataMapper;
import auracore.key49.queue.mapper.InvoiceDataMapper;
import auracore.key49.signer.CertificateEncryptor;
import auracore.key49.signer.XAdESBESSigner;
import auracore.key49.xml.accesskey.AccessKeyGenerator;
import auracore.key49.xml.builder.CreditNoteXmlBuilder;
import auracore.key49.xml.builder.DebitNoteXmlBuilder;
import auracore.key49.xml.builder.InvoiceXmlBuilder;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
    TenantRepository tenantRepository;

    @Inject
    TenantConnectionManager connectionManager;

    @Inject
    InvoiceDataMapper invoiceMapper;

    @Inject
    CreditNoteDataMapper creditNoteMapper;

    @Inject
    DebitNoteDataMapper debitNoteMapper;

    @ConfigProperty(name = "key49.master-key")
    Optional<String> masterKeyBase64;

    @Incoming("doc-sign-in")
    public Uni<Void> process(DocumentEvent event) {
        log.infof("SignConsumer: processing documentId=%s, tenant=%s",
                event.documentId(), event.tenantSchemaName());

        return tenantRepository.findBySchemaName(event.tenantSchemaName())
                .chain(tenant -> {
                    if (tenant == null) {
                        log.errorf("SignConsumer: tenant not found: %s", event.tenantSchemaName());
                        return Uni.createFrom().voidItem();
                    }
                    return connectionManager.withTenantTransaction(event.tenantSchemaName(), session
                            -> session.find(Document.class, event.documentId())
                                    .chain(doc -> {
                                        if (doc == null) {
                                            log.warnf("SignConsumer: document not found: %s", event.documentId());
                                            return Uni.createFrom().voidItem();
                                        }
                                        return signDocument(doc, tenant, session);
                                    })
                    );
                })
                .onFailure().invoke(ex
                        -> log.errorf(ex, "SignConsumer: unexpected error for documentId=%s", event.documentId()))
                .onFailure().recoverWithNull()
                .replaceWithVoid();
    }

    private Uni<Void> signDocument(Document doc, Tenant tenant, Mutiny.Session session) {
        if (!doc.status.canTransitionTo(DocumentStatus.SIGNED)) {
            log.warnf("SignConsumer: skip document %s in state %s", doc.id, doc.status);
            return Uni.createFrom().voidItem();
        }

        try {
            var docType = DocumentType.fromSriCode(doc.documentType);
            var sriEnv = resolveEnvironment(tenant.environment);

            var accessKey = AccessKeyGenerator.generate(
                    doc.issueDate, docType, tenant.ruc, sriEnv,
                    doc.establishment, doc.issuePoint, doc.sequenceNumber);

            var unsignedXml = buildXml(docType, doc, tenant, accessKey);

            var masterKey = CertificateEncryptor.decodeMasterKey(
                    masterKeyBase64.orElseThrow(()
                            -> new IllegalStateException("KEY49_MASTER_KEY not configured")));
            var password = CertificateEncryptor.decryptPassword(
                    tenant.certificatePasswordEnc, masterKey);
            var signedXml = XAdESBESSigner.sign(unsignedXml, tenant.certificateP12, password);

            doc.transitionTo(DocumentStatus.SIGNED);
            doc.accessKey = accessKey;
            doc.originalXml = signedXml;
            doc.updatedAt = Instant.now();

            var outboxEvent = OutboxEvent.create(doc.id, "doc.send", "{}");

            log.infof("SignConsumer: signed document %s, accessKey=%s", doc.id, accessKey);
            return session.persist(outboxEvent).replaceWithVoid();

        } catch (InvalidStateTransitionException e) {
            log.warnf("SignConsumer: invalid state transition for document %s: %s", doc.id, e.getMessage());
            return Uni.createFrom().voidItem();

        } catch (Exception e) {
            log.errorf(e, "SignConsumer: error signing document %s", doc.id);
            try {
                doc.transitionTo(DocumentStatus.FAILED);
            } catch (InvalidStateTransitionException iste) {
                log.warnf("SignConsumer: cannot transition to FAILED from %s for document %s",
                        doc.status, doc.id);
            }
            doc.lastErrorMessage = e.getMessage();
            doc.updatedAt = Instant.now();
            return Uni.createFrom().voidItem();
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
            default ->
                throw new UnsupportedOperationException(
                        "Document type not supported for signing: " + docType);
        };
    }
}
