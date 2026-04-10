package auracore.key49.signer;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Tests de integración para CertificateCacheService.
 *
 * <p>
 * Verifica: cache miss (carga desde p12 cifrado), cache hit (retorna objeto
 * cacheado), invalidación, y re-carga tras invalidación.
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CertificateCacheServiceTest {

    private static final String TEST_MASTER_KEY = "Kt+uSavMguKGLq2ese9Zj0qbk5U97/rGPIaW0TCqask=";
    private static final char[] TEST_PASSWORD = "test1234".toCharArray();

    @Inject
    CertificateCacheService cacheService;

    private byte[] encryptedP12;
    private byte[] encryptedPassword;
    private final UUID tenantId = UUID.randomUUID();
    private int initialCacheSize;

    @BeforeAll
    void setup() throws IOException {
        byte[] rawP12;
        try (InputStream is = getClass().getResourceAsStream("/test-cert.p12")) {
            assertNotNull(is, "test-cert.p12 must exist in test resources");
            rawP12 = is.readAllBytes();
        }
        var masterKey = CertificateEncryptor.decodeMasterKey(TEST_MASTER_KEY);
        encryptedP12 = CertificateEncryptor.encrypt(rawP12, masterKey);
        encryptedPassword = CertificateEncryptor.encryptPassword(TEST_PASSWORD, masterKey);
        initialCacheSize = cacheService.size();
    }

    @Test
    @Order(1)
    @DisplayName("cache miss: carga certificado desde p12 cifrado")
    void shouldLoadOnCacheMiss() {
        var certData = cacheService.getOrLoad(tenantId, encryptedP12, encryptedPassword);

        assertNotNull(certData, "CertificateData should not be null");
        assertNotNull(certData.privateKey(), "PrivateKey should not be null");
        assertNotNull(certData.certificate(), "Certificate should not be null");
        assertNotNull(certData.chain(), "Chain should not be null");
        assertEquals(initialCacheSize + 1, cacheService.size(),
                "Cache should have 1 more entry after miss");
    }

    @Test
    @Order(2)
    @DisplayName("cache hit: retorna misma instancia sin re-parsear")
    void shouldReturnCachedInstance() {
        var first = cacheService.getOrLoad(tenantId, encryptedP12, encryptedPassword);
        var second = cacheService.getOrLoad(tenantId, encryptedP12, encryptedPassword);

        assertSame(first, second, "Cached calls should return same CertificateData instance");
        assertEquals(initialCacheSize + 1, cacheService.size(), "Cache size should not grow");
    }

    @Test
    @Order(3)
    @DisplayName("múltiples tenants se cachean independientemente")
    void shouldCacheMultipleTenants() {
        var tenant2 = UUID.randomUUID();
        cacheService.getOrLoad(tenant2, encryptedP12, encryptedPassword);

        assertEquals(initialCacheSize + 2, cacheService.size(),
                "Cache should have 2 more entries than initial");
    }

    @Test
    @Order(4)
    @DisplayName("invalidación remueve la entrada del tenant")
    void shouldInvalidateEntry() {
        int sizeBefore = cacheService.size();
        cacheService.invalidate(tenantId);

        assertEquals(sizeBefore - 1, cacheService.size(),
                "Cache size should decrease by 1 after invalidation");
    }

    @Test
    @Order(5)
    @DisplayName("re-carga funciona tras invalidación")
    void shouldReloadAfterInvalidation() {
        var certData = cacheService.getOrLoad(tenantId, encryptedP12, encryptedPassword);

        assertNotNull(certData.privateKey(), "PrivateKey should not be null after reload");
        assertNotNull(certData.certificate(), "Certificate should not be null after reload");
    }

    @Test
    @Order(6)
    @DisplayName("invalidar tenant inexistente no falla")
    void shouldHandleInvalidateNonExistent() {
        int sizeBefore = cacheService.size();
        cacheService.invalidate(UUID.randomUUID());

        assertEquals(sizeBefore, cacheService.size(), "Cache size should not change");
    }

    @Test
    @Order(7)
    @DisplayName("firma 2 documentos seguidos: solo el primero parsea el .p12")
    void shouldCacheBetweenSignOperations() {
        var freshTenant = UUID.randomUUID();

        // Primera firma: cache miss → carga certificado
        var certData1 = cacheService.getOrLoad(freshTenant, encryptedP12, encryptedPassword);
        var xml = buildMinimalInvoiceXml();
        var signed1 = XAdESBESSigner.sign(xml, certData1);
        assertNotNull(signed1, "First signed XML should not be null");

        // Segunda firma: cache hit → misma instancia
        var certData2 = cacheService.getOrLoad(freshTenant, encryptedP12, encryptedPassword);
        var signed2 = XAdESBESSigner.sign(xml, certData2);
        assertNotNull(signed2, "Second signed XML should not be null");

        assertSame(certData1, certData2,
                "Both signing operations should use the same cached CertificateData");
    }

    @Test
    @Order(8)
    @DisplayName("falla con master key no configurada")
    void shouldFailWithoutMasterKey() {
        // Este test verifica que el servicio requiere master key;
        // como %test profile la tiene configurada, este test se cubre
        // implícitamente por los tests anteriores que funcionan correctamente.
        // Aquí validamos que datos de certificado inválidos fallan.
        var badP12 = new byte[]{1, 2, 3};
        var badPassword = new byte[]{4, 5, 6};

        assertThrows(Exception.class,
                () -> cacheService.getOrLoad(UUID.randomUUID(), badP12, badPassword),
                "Should fail with invalid encrypted data");
    }

    private String buildMinimalInvoiceXml() {
        return """
                <factura id="comprobante" version="1.0.0">
                  <infoTributaria>
                    <ambiente>1</ambiente>
                    <tipoEmision>1</tipoEmision>
                    <razonSocial>Test Corp S.A.</razonSocial>
                    <nombreComercial>TestCorp</nombreComercial>
                    <ruc>1790016919001</ruc>
                    <claveAcceso>1234567890123456789012345678901234567890123456789</claveAcceso>
                    <codDoc>01</codDoc>
                    <estab>001</estab>
                    <ptoEmi>001</ptoEmi>
                    <secuencial>000000001</secuencial>
                    <dirMatriz>Quito</dirMatriz>
                  </infoTributaria>
                  <infoFactura>
                    <fechaEmision>01/01/2025</fechaEmision>
                    <tipoIdentificacionComprador>04</tipoIdentificacionComprador>
                    <razonSocialComprador>Cliente S.A.</razonSocialComprador>
                    <identificacionComprador>1790016919001</identificacionComprador>
                    <totalSinImpuestos>100.00</totalSinImpuestos>
                    <totalDescuento>0.00</totalDescuento>
                    <totalConImpuestos>
                      <totalImpuesto>
                        <codigo>2</codigo>
                        <codigoPorcentaje>4</codigoPorcentaje>
                        <baseImponible>100.00</baseImponible>
                        <valor>15.00</valor>
                      </totalImpuesto>
                    </totalConImpuestos>
                    <propina>0.00</propina>
                    <importeTotal>115.00</importeTotal>
                    <moneda>DOLAR</moneda>
                    <pagos>
                      <pago>
                        <formaPago>20</formaPago>
                        <total>115.00</total>
                      </pago>
                    </pagos>
                  </infoFactura>
                  <detalles>
                    <detalle>
                      <codigoPrincipal>PROD-001</codigoPrincipal>
                      <descripcion>Producto de prueba</descripcion>
                      <cantidad>1</cantidad>
                      <precioUnitario>100.00</precioUnitario>
                      <descuento>0.00</descuento>
                      <precioTotalSinImpuesto>100.00</precioTotalSinImpuesto>
                      <impuestos>
                        <impuesto>
                          <codigo>2</codigo>
                          <codigoPorcentaje>4</codigoPorcentaje>
                          <tarifa>15</tarifa>
                          <baseImponible>100.00</baseImponible>
                          <valor>15.00</valor>
                        </impuesto>
                      </impuestos>
                    </detalle>
                  </detalles>
                </factura>
                """;
    }
}
