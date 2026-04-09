package auracore.key49.signer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import javax.xml.crypto.dom.DOMStructure;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Firmador XAdES-BES para comprobantes electrónicos del SRI de Ecuador.
 *
 * <p>
 * Genera una firma enveloped XAdES-BES conforme al estándar XAdES 1.3.2. La
 * firma se inserta como hijo del elemento raíz del comprobante (factura,
 * notaCredito, etc.) y referencia al nodo {@code id="comprobante"}.
 *
 * <p>
 * Estructura de la firma:
 * <ul>
 * <li>SignedInfo con 3 References: SignedProperties, KeyInfo, #comprobante</li>
 * <li>KeyInfo: X509Data (cadena completa de certificados) + RSAKeyValue</li>
 * <li>Object: QualifyingProperties con SignedProperties (SigningTime,
 * SigningCertificate, DataObjectFormat)</li>
 * </ul>
 *
 * <p>
 * Configuración:
 * <ul>
 * <li>Algoritmo de firma: RSA-SHA1 (requerido por el SRI)</li>
 * <li>Algoritmo de digest: SHA-1</li>
 * <li>Canonicalización: Inclusive C14N</li>
 * <li>Tipo de firma: Enveloped</li>
 * </ul>
 */
public final class XAdESBESSigner {

    private static final String XADES_NS = "http://uri.etsi.org/01903/v1.3.2#";
    private static final String DS_NS = XMLSignature.XMLNS;
    private static final ZoneId EC_ZONE = ZoneId.of("America/Guayaquil");

    private XAdESBESSigner() {
    }

    /**
     * Firma un XML de comprobante electrónico con XAdES-BES enveloped.
     *
     * @param xml contenido XML del comprobante (sin firmar)
     * @param p12Bytes contenido del certificado PKCS#12 (.p12)
     * @param password contraseña del certificado
     * @return XML firmado con ds:Signature + XAdES QualifyingProperties
     * @throws SigningException si ocurre un error durante la firma
     */
    public static String sign(String xml, byte[] p12Bytes, char[] password) {
        try {
            var certData = loadCertificateData(p12Bytes, password);
            var document = parseXml(xml);
            signDocument(document, certData);
            return serializeDocument(document);
        } catch (SigningException e) {
            throw e;
        } catch (Exception e) {
            throw new SigningException("Failed to sign XML document", e);
        }
    }

    /**
     * Carga la clave privada, certificado y cadena completa desde un PKCS#12.
     */
    static CertificateData loadCertificateData(byte[] p12Bytes, char[] password) {
        try {
            var keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new ByteArrayInputStream(p12Bytes), password);

            var alias = findSigningAlias(keyStore);
            var privateKey = (PrivateKey) keyStore.getKey(alias, password);
            var certificate = (X509Certificate) keyStore.getCertificate(alias);

            if (privateKey == null) {
                throw new SigningException("No private key found for alias: " + alias);
            }
            if (certificate == null) {
                throw new SigningException("No certificate found for alias: " + alias);
            }

            var rawChain = keyStore.getCertificateChain(alias);
            X509Certificate[] chain;
            if (rawChain != null && rawChain.length > 0) {
                chain = new X509Certificate[rawChain.length];
                for (int i = 0; i < rawChain.length; i++) {
                    chain[i] = (X509Certificate) rawChain[i];
                }
            } else {
                chain = new X509Certificate[]{certificate};
            }

            return new CertificateData(privateKey, certificate, chain);
        } catch (KeyStoreException | IOException | NoSuchAlgorithmException
                | CertificateException | UnrecoverableKeyException e) {
            throw new SigningException("Failed to load PKCS#12 certificate", e);
        }
    }

    /**
     * Busca el primer alias con clave privada en el KeyStore.
     */
    private static String findSigningAlias(KeyStore keyStore) throws KeyStoreException {
        var aliases = Collections.list(keyStore.aliases());
        for (var alias : aliases) {
            if (keyStore.isKeyEntry(alias)) {
                return alias;
            }
        }
        throw new SigningException("No key entry found in PKCS#12 keystore");
    }

    /**
     * Parsea un XML string a un Document DOM namespace-aware.
     */
    private static Document parseXml(String xml) throws ParserConfigurationException, IOException, SAXException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    /**
     * Firma el Document DOM con XAdES-BES enveloped.
     *
     * <p>
     * Genera 3 References en SignedInfo:
     * <ol>
     * <li>SignedProperties (XAdES) - digest de las propiedades firmadas</li>
     * <li>KeyInfo - digest de la información del certificado</li>
     * <li>#comprobante - digest del contenido del comprobante (enveloped)</li>
     * </ol>
     */
    private static void signDocument(Document document, CertificateData certData) throws Exception {
        var rnd = ThreadLocalRandom.current();
        var signatureId = "Signature" + rnd.nextInt(100000, 1000000);
        var signedInfoId = "Signature-SignedInfo" + rnd.nextInt(100000, 1000000);
        var signedPropsRefId = "SignedPropertiesID" + rnd.nextInt(100000, 1000000);
        var signedPropertiesId = signatureId + "-SignedProperties" + rnd.nextInt(100000, 1000000);
        var keyInfoId = "Certificate" + rnd.nextInt(100000, 1000000);
        var referenceId = "Reference-ID-" + rnd.nextInt(100000, 1000000);
        var signatureValueId = "SignatureValue" + rnd.nextInt(100000, 1000000);
        var objectId = signatureId + "-Object" + rnd.nextInt(100000, 1000000);

        var fac = XMLSignatureFactory.getInstance("DOM");

        // Construir XAdES QualifyingProperties como nodos DOM
        var qualifyingProps = buildQualifyingProperties(
                document, signatureId, signedPropertiesId, referenceId,
                certData.certificate());

        var signedPropsEl = findElementByAttribute(qualifyingProps, "Id", signedPropertiesId);

        // Registrar Id en el DOM para que getElementById() lo encuentre
        document.getDocumentElement().setIdAttributeNS(null, "id", true);
        if (signedPropsEl != null) {
            signedPropsEl.setIdAttributeNS(null, "Id", true);
        }

        // Reference 1: XAdES SignedProperties
        var refSignedProps = fac.newReference(
                "#" + signedPropertiesId,
                fac.newDigestMethod(DigestMethod.SHA1, null),
                Collections.emptyList(),
                "http://uri.etsi.org/01903#SignedProperties",
                signedPropsRefId);

        // Reference 2: KeyInfo (datos del certificado)
        var refKeyInfo = fac.newReference(
                "#" + keyInfoId,
                fac.newDigestMethod(DigestMethod.SHA1, null));

        // Reference 3: contenido del comprobante con transform enveloped
        var refComprobante = fac.newReference(
                "#comprobante",
                fac.newDigestMethod(DigestMethod.SHA1, null),
                List.of(fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
                null, referenceId);

        // SignedInfo con C14N y RSA-SHA1
        var signedInfo = fac.newSignedInfo(
                fac.newCanonicalizationMethod(
                        CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
                fac.newSignatureMethod(SignatureMethod.RSA_SHA1, null),
                List.of(refSignedProps, refKeyInfo, refComprobante),
                signedInfoId);

        // KeyInfo: X509Data (solo certificado firmante) + RSAKeyValue
        var kif = fac.getKeyInfoFactory();
        var x509Data = kif.newX509Data(List.of(certData.certificate()));
        var keyValue = kif.newKeyValue(certData.certificate().getPublicKey());
        var keyInfo = kif.newKeyInfo(List.of(x509Data, keyValue), keyInfoId);

        // ds:Object con QualifyingProperties
        var xmlObject = fac.newXMLObject(
                List.of(new DOMStructure(qualifyingProps)),
                objectId, null, null);

        // Crear XMLSignature
        var xmlSignature = fac.newXMLSignature(
                signedInfo, keyInfo, List.of(xmlObject), signatureId, signatureValueId);

        // Configurar contexto de firma con prefijo ds:
        var signContext = new DOMSignContext(
                certData.privateKey(), document.getDocumentElement());
        signContext.setDefaultNamespacePrefix("ds");

        // Registrar atributos Id para resolución de URIs durante cómputo de digests
        signContext.setIdAttributeNS(document.getDocumentElement(), null, "id");
        if (signedPropsEl != null) {
            signContext.setIdAttributeNS(signedPropsEl, null, "Id");
        }

        xmlSignature.sign(signContext);
    }

    /**
     * Construye el elemento etsi:QualifyingProperties con SignedProperties para
     * la firma XAdES-BES.
     */
    private static Element buildQualifyingProperties(Document doc, String signatureId,
            String signedPropertiesId, String referenceId, X509Certificate cert)
            throws NoSuchAlgorithmException, CertificateEncodingException {

        var qp = doc.createElementNS(XADES_NS, "etsi:QualifyingProperties");
        qp.setAttribute("Target", "#" + signatureId);

        var sp = doc.createElementNS(XADES_NS, "etsi:SignedProperties");
        sp.setAttribute("Id", signedPropertiesId);
        qp.appendChild(sp);

        // ── SignedSignatureProperties: SigningTime + SigningCertificate ──
        var ssp = doc.createElementNS(XADES_NS, "etsi:SignedSignatureProperties");
        sp.appendChild(ssp);

        var signingTime = doc.createElementNS(XADES_NS, "etsi:SigningTime");
        signingTime.setTextContent(
                ZonedDateTime.now(EC_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        ssp.appendChild(signingTime);

        var signingCert = doc.createElementNS(XADES_NS, "etsi:SigningCertificate");
        ssp.appendChild(signingCert);

        var certEl = doc.createElementNS(XADES_NS, "etsi:Cert");
        signingCert.appendChild(certEl);

        var certDigest = doc.createElementNS(XADES_NS, "etsi:CertDigest");
        certEl.appendChild(certDigest);

        var digestMethod = doc.createElementNS(DS_NS, "ds:DigestMethod");
        digestMethod.setAttribute("Algorithm", DigestMethod.SHA1);
        certDigest.appendChild(digestMethod);

        var digestValue = doc.createElementNS(DS_NS, "ds:DigestValue");
        digestValue.setTextContent(computeCertificateDigest(cert));
        certDigest.appendChild(digestValue);

        var issuerSerial = doc.createElementNS(XADES_NS, "etsi:IssuerSerial");
        certEl.appendChild(issuerSerial);

        var issuerName = doc.createElementNS(DS_NS, "ds:X509IssuerName");
        issuerName.setTextContent(cert.getIssuerX500Principal().getName());
        issuerSerial.appendChild(issuerName);

        var serialNumber = doc.createElementNS(DS_NS, "ds:X509SerialNumber");
        serialNumber.setTextContent(cert.getSerialNumber().toString());
        issuerSerial.appendChild(serialNumber);

        // ── SignedDataObjectProperties: DataObjectFormat ──
        var sdop = doc.createElementNS(XADES_NS, "etsi:SignedDataObjectProperties");
        sp.appendChild(sdop);

        var dof = doc.createElementNS(XADES_NS, "etsi:DataObjectFormat");
        dof.setAttribute("ObjectReference", "#" + referenceId);
        sdop.appendChild(dof);

        var desc = doc.createElementNS(XADES_NS, "etsi:Description");
        desc.setTextContent("contenido comprobante");
        dof.appendChild(desc);

        var mimeType = doc.createElementNS(XADES_NS, "etsi:MimeType");
        mimeType.setTextContent("text/xml");
        dof.appendChild(mimeType);

        return qp;
    }

    private static String computeCertificateDigest(X509Certificate cert)
            throws NoSuchAlgorithmException, CertificateEncodingException {
        var md = MessageDigest.getInstance("SHA-1");
        return Base64.getEncoder().encodeToString(md.digest(cert.getEncoded()));
    }

    /**
     * Busca recursivamente un elemento por valor de atributo.
     */
    private static Element findElementByAttribute(Element root, String attrName, String attrValue) {
        if (attrValue.equals(root.getAttribute(attrName))) {
            return root;
        }
        var children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                var found = findElementByAttribute(child, attrName, attrValue);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Serializa un Document DOM a String XML.
     */
    private static String serializeDocument(Document document) throws TransformerException {
        var transformerFactory = TransformerFactory.newInstance();
        var transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.INDENT, "no");

        var writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    /**
     * Datos extraídos del certificado PKCS#12 incluyendo la cadena completa.
     */
    record CertificateData(PrivateKey privateKey, X509Certificate certificate, X509Certificate[] chain) {

    }
}
