package auracore.key49.signer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.security.InvalidAlgorithmParameterException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.xml.crypto.MarshalException;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
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
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Firmador XAdES-BES para comprobantes electrónicos del SRI de Ecuador.
 *
 * <p>Genera una firma enveloped XML-DSig conforme al estándar XAdES-BES 1.3.2.
 * La firma se inserta como hijo del elemento raíz del comprobante
 * (factura, notaCredito, etc.).
 *
 * <p>Configuración:
 * <ul>
 *   <li>Algoritmo de firma: RSA-SHA1 (requerido por el SRI)</li>
 *   <li>Algoritmo de digest: SHA-1</li>
 *   <li>Canonicalización: Inclusive C14N</li>
 *   <li>Tipo de firma: Enveloped</li>
 * </ul>
 */
public final class XAdESBESSigner {

    private XAdESBESSigner() {
    }

    /**
     * Firma un XML de comprobante electrónico con XAdES-BES enveloped.
     *
     * @param xml      contenido XML del comprobante (sin firmar)
     * @param p12Bytes contenido del certificado PKCS#12 (.p12)
     * @param password contraseña del certificado
     * @return XML firmado con ds:Signature insertado
     * @throws SigningException si ocurre un error durante la firma
     */
    public static String sign(String xml, byte[] p12Bytes, char[] password) {
        try {
            var certData = loadCertificateData(p12Bytes, password);
            var document = parseXml(xml);
            signDocument(document, certData.privateKey(), certData.certificate());
            return serializeDocument(document);
        } catch (SigningException e) {
            throw e;
        } catch (Exception e) {
            throw new SigningException("Failed to sign XML document", e);
        }
    }

    /**
     * Carga la clave privada y certificado desde un PKCS#12.
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

            return new CertificateData(privateKey, certificate);
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
     * Parsea un XML string a un Document DOM.
     */
    private static Document parseXml(String xml) throws ParserConfigurationException, IOException, SAXException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    /**
     * Firma el Document DOM con XML-DSig enveloped.
     */
    private static void signDocument(Document document, PrivateKey privateKey, X509Certificate certificate)
            throws NoSuchAlgorithmException, InvalidAlgorithmParameterException,
            MarshalException, XMLSignatureException {

        var signatureFactory = XMLSignatureFactory.getInstance("DOM");

        // Reference: firma del documento completo con transform enveloped
        var envelopedTransform = signatureFactory.newTransform(
                Transform.ENVELOPED, (TransformParameterSpec) null);
        var reference = signatureFactory.newReference(
                "",
                signatureFactory.newDigestMethod(DigestMethod.SHA1, null),
                List.of(envelopedTransform),
                null, null);

        // SignedInfo con C14N y RSA-SHA1
        var signedInfo = signatureFactory.newSignedInfo(
                signatureFactory.newCanonicalizationMethod(
                        CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
                signatureFactory.newSignatureMethod(SignatureMethod.RSA_SHA1, null),
                List.of(reference));

        // KeyInfo con certificado X.509
        var keyInfoFactory = signatureFactory.getKeyInfoFactory();
        var x509Content = new ArrayList<>();
        x509Content.add(certificate);
        var x509Data = keyInfoFactory.newX509Data(x509Content);
        var keyInfo = keyInfoFactory.newKeyInfo(List.of(x509Data));

        // Crear y ejecutar la firma en el nodo raíz del comprobante
        var xmlSignature = signatureFactory.newXMLSignature(signedInfo, keyInfo);
        var signContext = new DOMSignContext(privateKey, document.getDocumentElement());
        xmlSignature.sign(signContext);
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
     * Datos extraídos del certificado PKCS#12.
     */
    record CertificateData(PrivateKey privateKey, X509Certificate certificate) {
    }
}
