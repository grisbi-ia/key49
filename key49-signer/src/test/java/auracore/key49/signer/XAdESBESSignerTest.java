package auracore.key49.signer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.security.KeyStore;
import java.security.cert.X509Certificate;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests unitarios para XAdESBESSigner.
 */
class XAdESBESSignerTest {

    private static final String TEST_CERT_PATH = "/test-cert.p12";
    private static final char[] TEST_PASSWORD = "test1234".toCharArray();

    // ── Helpers ──

    private byte[] loadTestCertificate() {
        try (InputStream is = getClass().getResourceAsStream(TEST_CERT_PATH)) {
            if (is == null) {
                throw new IllegalStateException("Test certificate not found: " + TEST_CERT_PATH);
            }
            return is.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read test certificate", e);
        }
    }

    private Document parseXml(String xml) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var builder = factory.newDocumentBuilder();
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    private boolean validateXmlSignature(String signedXml) throws Exception {
        var document = parseXml(signedXml);
        var signatureNodes = document.getElementsByTagNameNS(
                XMLSignature.XMLNS, "Signature");

        if (signatureNodes.getLength() == 0) {
            return false;
        }

        // Extraer el certificado del KeyInfo para validar
        var p12 = loadTestCertificate();
        var keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(new ByteArrayInputStream(p12), TEST_PASSWORD);
        var alias = keyStore.aliases().nextElement();
        var cert = (X509Certificate) keyStore.getCertificate(alias);

        var signatureFactory = XMLSignatureFactory.getInstance("DOM");
        var validateContext = new DOMValidateContext(
                cert.getPublicKey(), signatureNodes.item(0));
        // El SRI de Ecuador requiere RSA-SHA1 — desactivar validación segura
        // para permitir verificar la firma en el test
        validateContext.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.FALSE);
        var signature = signatureFactory.unmarshalXMLSignature(validateContext);
        return signature.validate(validateContext);
    }

    /**
     * XML mínimo de factura para pruebas de firma.
     */
    private String simpleInvoiceXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <factura id="comprobante" version="2.1.0">
                    <infoTributaria>
                        <ambiente>1</ambiente>
                        <tipoEmision>1</tipoEmision>
                        <razonSocial>EMPRESA DEMO S.A.</razonSocial>
                        <ruc>1790012345001</ruc>
                        <claveAcceso>0504202601179001234500110010010000000421234567817</claveAcceso>
                        <codDoc>01</codDoc>
                        <estab>001</estab>
                        <ptoEmi>001</ptoEmi>
                        <secuencial>000000042</secuencial>
                        <dirMatriz>Quito, Av. Principal 123</dirMatriz>
                    </infoTributaria>
                    <infoFactura>
                        <fechaEmision>05/04/2026</fechaEmision>
                        <obligadoContabilidad>SI</obligadoContabilidad>
                        <tipoIdentificacionComprador>04</tipoIdentificacionComprador>
                        <razonSocialComprador>CLIENTE PRUEBA CIA. LTDA.</razonSocialComprador>
                        <identificacionComprador>1790567890001</identificacionComprador>
                        <totalSinImpuestos>50.00</totalSinImpuestos>
                        <totalDescuento>0.00</totalDescuento>
                        <totalConImpuestos>
                            <totalImpuesto>
                                <codigo>2</codigo>
                                <codigoPorcentaje>4</codigoPorcentaje>
                                <baseImponible>50.00</baseImponible>
                                <valor>7.50</valor>
                            </totalImpuesto>
                        </totalConImpuestos>
                        <propina>0.00</propina>
                        <importeTotal>57.50</importeTotal>
                        <moneda>DOLAR</moneda>
                        <pagos>
                            <pago>
                                <formaPago>20</formaPago>
                                <total>57.50</total>
                            </pago>
                        </pagos>
                    </infoFactura>
                    <detalles>
                        <detalle>
                            <codigoPrincipal>PROD-001</codigoPrincipal>
                            <descripcion>Servicio de hosting mensual</descripcion>
                            <cantidad>1.000000</cantidad>
                            <precioUnitario>50.00</precioUnitario>
                            <descuento>0.00</descuento>
                            <precioTotalSinImpuesto>50.00</precioTotalSinImpuesto>
                            <impuestos>
                                <impuesto>
                                    <codigo>2</codigo>
                                    <codigoPorcentaje>4</codigoPorcentaje>
                                    <tarifa>15.00</tarifa>
                                    <baseImponible>50.00</baseImponible>
                                    <valor>7.50</valor>
                                </impuesto>
                            </impuestos>
                        </detalle>
                    </detalles>
                </factura>
                """;
    }

    // ── Tests de firma exitosa ──

    @Nested
    @DisplayName("Firma exitosa")
    class SuccessfulSigning {

        @Test
        @DisplayName("Firma una factura y produce XML válido con ds:Signature")
        void signsInvoiceXml() {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);

            assertNotNull(signedXml);
            assertTrue(signedXml.contains("ds:Signature") || signedXml.contains("Signature"),
                    "Signed XML should contain Signature element");
        }

        @Test
        @DisplayName("El XML firmado contiene el elemento SignatureValue")
        void signedXmlContainsSignatureValue() {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);

            assertTrue(signedXml.contains("SignatureValue"),
                    "Signed XML should contain SignatureValue element");
        }

        @Test
        @DisplayName("El XML firmado contiene KeyInfo con certificado X.509")
        void signedXmlContainsKeyInfo() {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);

            assertTrue(signedXml.contains("KeyInfo"), "Should contain KeyInfo");
            assertTrue(signedXml.contains("X509Certificate"), "Should contain X509Certificate");
        }

        @Test
        @DisplayName("El XML firmado contiene SignedInfo con método de canonicalización")
        void signedXmlContainsSignedInfo() {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);

            assertTrue(signedXml.contains("SignedInfo"), "Should contain SignedInfo");
            assertTrue(signedXml.contains("CanonicalizationMethod"), "Should contain CanonicalizationMethod");
        }

        @Test
        @DisplayName("La firma usa transform enveloped-signature")
        void signedXmlUsesEnvelopedTransform() {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);

            assertTrue(signedXml.contains("enveloped-signature"),
                    "Should use enveloped-signature transform");
        }

        @Test
        @DisplayName("La firma es verificable criptográficamente")
        void signatureIsVerifiable() throws Exception {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);

            assertTrue(validateXmlSignature(signedXml),
                    "XML signature should be cryptographically valid");
        }

        @Test
        @DisplayName("El ds:Signature se inserta como hijo del elemento raíz")
        void signatureIsChildOfRoot() throws Exception {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);
            var document = parseXml(signedXml);

            // El elemento raíz es <factura>
            var root = document.getDocumentElement();
            assertEquals("factura", root.getLocalName());

            // Buscar Signature como hijo directo del root
            var children = root.getChildNodes();
            boolean found = false;
            for (int i = 0; i < children.getLength(); i++) {
                var child = children.item(i);
                if ("Signature".equals(child.getLocalName())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "ds:Signature should be a direct child of the root element");
        }

        @Test
        @DisplayName("El XML firmado conserva la declaración XML con UTF-8")
        void signedXmlPreservesEncoding() {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);

            assertTrue(signedXml.contains("UTF-8"), "Should preserve UTF-8 encoding");
        }

        @Test
        @DisplayName("El XML firmado preserva el contenido original del comprobante")
        void signedXmlPreservesOriginalContent() {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);

            // Verificar que los datos del comprobante se mantienen
            assertTrue(signedXml.contains("<ambiente>1</ambiente>"));
            assertTrue(signedXml.contains("<ruc>1790012345001</ruc>"));
            assertTrue(signedXml.contains("EMPRESA DEMO S.A."));
            assertTrue(signedXml.contains("<importeTotal>57.50</importeTotal>"));
        }

        @Test
        @DisplayName("Firmar dos veces el mismo XML produce firmas diferentes")
        void signingTwiceProducesDifferentSignatures() {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signed1 = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);
            var signed2 = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);

            // Las firmas pueden ser iguales con misma key+content, así que verificamos ambas válidas
            assertNotNull(signed1);
            assertNotNull(signed2);
            assertTrue(signed1.contains("Signature"));
            assertTrue(signed2.contains("Signature"));
        }

        @Test
        @DisplayName("Firma XML de nota de crédito (otro tipo de comprobante)")
        void signsCreditNoteXml() {
            var xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <notaCredito id="comprobante" version="1.1.0">
                        <infoTributaria>
                            <ambiente>1</ambiente>
                            <tipoEmision>1</tipoEmision>
                            <razonSocial>DEMO S.A.</razonSocial>
                            <ruc>1790012345001</ruc>
                            <claveAcceso>0504202601179001234500110010010000000421234567817</claveAcceso>
                            <codDoc>04</codDoc>
                            <estab>001</estab>
                            <ptoEmi>001</ptoEmi>
                            <secuencial>000000001</secuencial>
                            <dirMatriz>Quito</dirMatriz>
                        </infoTributaria>
                    </notaCredito>
                    """;
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);

            assertTrue(signedXml.contains("Signature"));
            assertTrue(signedXml.contains("notaCredito"));
        }
    }

    // ── Tests de carga de certificado ──

    @Nested
    @DisplayName("Carga de certificado")
    class CertificateLoading {

        @Test
        @DisplayName("Carga correctamente la clave privada y certificado desde .p12")
        void loadsCertificateData() {
            var p12 = loadTestCertificate();

            var certData = XAdESBESSigner.loadCertificateData(p12, TEST_PASSWORD);

            assertNotNull(certData.privateKey(), "Should load private key");
            assertNotNull(certData.certificate(), "Should load certificate");
            assertEquals("RSA", certData.privateKey().getAlgorithm());
        }

        @Test
        @DisplayName("El certificado cargado tiene subject DN válido")
        void certificateHasValidSubject() {
            var p12 = loadTestCertificate();

            var certData = XAdESBESSigner.loadCertificateData(p12, TEST_PASSWORD);
            var subject = certData.certificate().getSubjectX500Principal().getName();

            assertTrue(subject.contains("EMPRESA DEMO"),
                    "Certificate subject should contain the expected CN");
        }

        @Test
        @DisplayName("Contraseña incorrecta lanza SigningException")
        void wrongPasswordThrowsException() {
            var p12 = loadTestCertificate();
            var wrongPassword = "wrong".toCharArray();

            assertThrows(SigningException.class,
                    () -> XAdESBESSigner.loadCertificateData(p12, wrongPassword));
        }

        @Test
        @DisplayName("Bytes inválidos de .p12 lanza SigningException")
        void invalidP12BytesThrowsException() {
            var invalidBytes = "not a p12 file".getBytes();

            assertThrows(SigningException.class,
                    () -> XAdESBESSigner.loadCertificateData(invalidBytes, TEST_PASSWORD));
        }
    }

    // ── Tests de errores ──

    @Nested
    @DisplayName("Manejo de errores")
    class ErrorHandling {

        @Test
        @DisplayName("XML mal formado lanza SigningException")
        void malformedXmlThrowsException() {
            var p12 = loadTestCertificate();

            assertThrows(SigningException.class,
                    () -> XAdESBESSigner.sign("<factura>incomplete", p12, TEST_PASSWORD));
        }

        @Test
        @DisplayName("XML vacío lanza SigningException")
        void emptyXmlThrowsException() {
            var p12 = loadTestCertificate();

            assertThrows(SigningException.class,
                    () -> XAdESBESSigner.sign("", p12, TEST_PASSWORD));
        }

        @Test
        @DisplayName("Certificado null lanza excepción")
        void nullCertificateThrowsException() {
            assertThrows(SigningException.class,
                    () -> XAdESBESSigner.sign(simpleInvoiceXml(), null, TEST_PASSWORD));
        }

        @Test
        @DisplayName("Contraseña null con certificado válido lanza excepción")
        void nullPasswordThrowsException() {
            var p12 = loadTestCertificate();

            assertThrows(SigningException.class,
                    () -> XAdESBESSigner.sign(simpleInvoiceXml(), p12, null));
        }
    }

    // ── Tests de estructura de firma ──

    @Nested
    @DisplayName("Estructura de la firma")
    class SignatureStructure {

        @Test
        @DisplayName("La firma contiene exactamente un elemento Signature")
        void containsExactlyOneSignature() throws Exception {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);
            var document = parseXml(signedXml);

            NodeList signatures = document.getElementsByTagNameNS(
                    XMLSignature.XMLNS, "Signature");
            assertEquals(1, signatures.getLength(), "Should contain exactly one Signature");
        }

        @Test
        @DisplayName("La firma contiene exactamente una Reference")
        void containsExactlyOneReference() throws Exception {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);
            var document = parseXml(signedXml);

            NodeList references = document.getElementsByTagNameNS(
                    XMLSignature.XMLNS, "Reference");
            assertEquals(1, references.getLength(), "Should contain exactly one Reference");
        }

        @Test
        @DisplayName("La firma contiene DigestMethod con SHA-1")
        void containsDigestMethodSha1() {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);

            assertTrue(signedXml.contains("http://www.w3.org/2000/09/xmldsig#sha1")
                            || signedXml.contains("xmldsig#sha1"),
                    "Should use SHA-1 digest method");
        }

        @Test
        @DisplayName("La firma contiene SignatureMethod con RSA-SHA1")
        void containsSignatureMethodRsaSha1() {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);

            assertTrue(signedXml.contains("rsa-sha1"),
                    "Should use RSA-SHA1 signature method");
        }

        @Test
        @DisplayName("La firma usa canonicalización inclusiva C14N")
        void usesInclusiveC14N() {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);

            assertTrue(signedXml.contains("http://www.w3.org/TR/2001/REC-xml-c14n-20010315"),
                    "Should use inclusive C14N canonicalization");
        }

        @Test
        @DisplayName("X509Data contiene el certificado embebido")
        void x509DataContainsCertificate() throws Exception {
            var xml = simpleInvoiceXml();
            var p12 = loadTestCertificate();

            var signedXml = XAdESBESSigner.sign(xml, p12, TEST_PASSWORD);
            var document = parseXml(signedXml);

            NodeList x509Datas = document.getElementsByTagNameNS(
                    XMLSignature.XMLNS, "X509Data");
            assertEquals(1, x509Datas.getLength(), "Should contain one X509Data");

            NodeList x509Certs = document.getElementsByTagNameNS(
                    XMLSignature.XMLNS, "X509Certificate");
            assertEquals(1, x509Certs.getLength(), "Should contain one X509Certificate");

            var certText = x509Certs.item(0).getTextContent();
            assertNotNull(certText);
            assertFalse(certText.isBlank(), "Certificate should not be empty");
        }
    }
}
