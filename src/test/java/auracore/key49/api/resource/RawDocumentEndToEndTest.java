package auracore.key49.api.resource;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import auracore.key49.core.Key49Constants;
import auracore.key49.core.service.ApiKeyService;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import jakarta.inject.Inject;

/**
 * Test end-to-end del endpoint POST /v1/documents/raw y GET
 * /v1/documents/raw/:id.
 *
 * <p>
 * Verifica: envío de XML válido, validación XSD, mismatch de tipo, header
 * faltante, y consulta por ID.</p>
 */
@QuarkusTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RawDocumentEndToEndTest {

    private static final String TENANT_SCHEMA = "tenant_raw_e2e";
    private static final String TENANT_RUC = "1790016919001";
    private static final DateTimeFormatter SRI_DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Inject
    javax.sql.DataSource dataSource;

    private String rawApiKey;
    private UUID tenantId;
    private String createdDocumentId;

    @BeforeAll
    void setupTenantAndApiKey() throws SQLException {
        tenantId = UUID.randomUUID();
        var generated = ApiKeyService.generate();
        rawApiKey = generated.rawKey();

        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("""
INSERT INTO tenants (tenant_id, ruc, legal_name, trade_name, main_address, schema_name,
                            required_accounting, micro_enterprise_regime, environment,
                            emission_type, rate_limit_rpm, rate_limit_write_rpm, rate_limit_read_rpm, status, created_at, updated_at)
                        VALUES (?::uuid, ?, ?, ?, ?, ?, false, false, 'test', 1, 10000, 10000, 10000, 'active', now(), now())
            """)) {
                ps.setObject(1, tenantId.toString());
                ps.setString(2, TENANT_RUC);
                ps.setString(3, "Raw E2E Test Corp S.A.");
                ps.setString(4, "Raw E2E");
                ps.setString(5, "Quito");
                ps.setString(6, TENANT_SCHEMA);
                ps.executeUpdate();
            }

            try (var ps2 = conn.prepareStatement("""
                    INSERT INTO api_keys (api_key_id, tenant_id, key_prefix, key_hash, name, permissions, status, created_at)
                    VALUES (?::uuid, ?::uuid, ?, ?, ?, '*', 'active', now())""")) {
                ps2.setObject(1, UUID.randomUUID().toString());
                ps2.setObject(2, tenantId.toString());
                ps2.setString(3, generated.keyPrefix());
                ps2.setString(4, generated.hash());
                ps2.setString(5, "raw-e2e-key");
                ps2.executeUpdate();
            }

            try (var stmt = conn.createStatement()) {
                stmt.execute("CREATE SCHEMA IF NOT EXISTS " + TENANT_SCHEMA);
                stmt.execute("""
        CREATE TABLE IF NOT EXISTS %s.documents (
                            document_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            document_type VARCHAR(2) NOT NULL,
                            establishment VARCHAR(3) NOT NULL,
                            issue_point VARCHAR(3) NOT NULL,
                            sequence_number VARCHAR(9) NOT NULL,
                            access_key VARCHAR(49),
                            authorization_number VARCHAR(49),
                            request_origin VARCHAR(10) NOT NULL DEFAULT 'JSON',
                            recipient_id_type VARCHAR(2) NOT NULL,
                            recipient_id VARCHAR(20) NOT NULL,
                            recipient_name VARCHAR(300) NOT NULL,
                            recipient_email VARCHAR(500),
                            recipient_address VARCHAR(300),
                            recipient_phone VARCHAR(50),
                            subtotal_before_tax NUMERIC(14,2) NOT NULL DEFAULT 0,
                            total_discount NUMERIC(14,2) NOT NULL DEFAULT 0,
                            subtotal_vat_0 NUMERIC(14,2) NOT NULL DEFAULT 0,
                            subtotal_vat_12 NUMERIC(14,2) NOT NULL DEFAULT 0,
                            subtotal_vat_15 NUMERIC(14,2) NOT NULL DEFAULT 0,
                            subtotal_non_taxable NUMERIC(14,2) NOT NULL DEFAULT 0,
                            subtotal_exempt NUMERIC(14,2) NOT NULL DEFAULT 0,
                            vat_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
                            ice_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
                            tip NUMERIC(14,2) NOT NULL DEFAULT 0,
                            total_amount NUMERIC(14,2) NOT NULL DEFAULT 0,
                            currency VARCHAR(15) NOT NULL DEFAULT 'DOLAR',
                            issue_date DATE NOT NULL,
                            authorization_date TIMESTAMPTZ,
                            sri_submission_date TIMESTAMPTZ,
                            status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
                            retry_count SMALLINT NOT NULL DEFAULT 0,
                            max_retries SMALLINT NOT NULL DEFAULT 6,
                            next_retry_at TIMESTAMPTZ,
                            last_error_code VARCHAR(10),
                            last_error_message TEXT,
                            sri_messages JSONB,
                            unsigned_xml_path VARCHAR(500),
                            signed_xml_path VARCHAR(500),
                            authorized_xml_path VARCHAR(500),
                            ride_path VARCHAR(500),
                            request_payload JSONB,
                            original_xml TEXT,
                            request_ip VARCHAR(45),
                            idempotency_key VARCHAR(50),
                            email_sent_at TIMESTAMPTZ,
                            email_status VARCHAR(20),
                            email_error VARCHAR(500),
                            voided_at TIMESTAMPTZ,
                            void_reason VARCHAR(500),
                            version INT NOT NULL DEFAULT 1,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                            CONSTRAINT uq_raw_e2e_doc_number UNIQUE (document_type, establishment, issue_point, sequence_number),
                            CONSTRAINT uq_raw_e2e_doc_access_key UNIQUE (access_key),
                            CONSTRAINT uq_raw_e2e_doc_idempotency UNIQUE (idempotency_key)
                        )
                        """.formatted(TENANT_SCHEMA));
                stmt.execute("""
        CREATE TABLE IF NOT EXISTS %s.outbox (
                            outbox_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                            aggregate_type VARCHAR(50) NOT NULL,
                            aggregate_id UUID NOT NULL,
                            event_type VARCHAR(50) NOT NULL,
                            payload JSONB NOT NULL,
                            published BOOLEAN NOT NULL DEFAULT false,
                            published_at TIMESTAMPTZ,
                            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                        )
                        """.formatted(TENANT_SCHEMA));
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private String authHeader() {
        return "Bearer " + rawApiKey;
    }

    private LocalDate today() {
        return LocalDate.now(Key49Constants.EC_ZONE);
    }

    private String todaySriFormat() {
        return today().format(SRI_DATE_FORMAT);
    }

    // ── 1. Enviar factura XML válida ──
    @Test
    @Order(1)
    void shouldCreateRawDocument_returns202() {
        var xml = buildValidInvoiceXml("000000001");

        createdDocumentId = RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "raw-idem-001")
                .header("X-Document-Type", "01")
                .contentType("application/xml")
                .body(xml)
                .when()
                .post("/v1/documents/raw")
                .then()
                .statusCode(202)
                .header("X-Request-Id", startsWith("req_"))
                .body("data.id", notNullValue())
                .body("data.status", equalTo("CREATED"))
                .body("data.origin", equalTo("XML_RAW"))
                .body("data.document_type", equalTo("01"))
                .body("data.establishment", equalTo("001"))
                .body("data.issue_point", equalTo("001"))
                .body("data.sequence_number", equalTo("000000001"))
                .body("data.access_key", notNullValue())
                .body("data.total_amount", equalTo(57.5F))
                .body("data.recipient.id", equalTo("1790016919001"))
                .body("data.recipient.name", equalTo("CLIENTE PRUEBA CIA. LTDA."))
                .body("meta.request_id", notNullValue())
                .extract()
                .<String>path("data.id");
    }

    // ── 2. Consultar documento raw por ID ──
    @Test
    @Order(2)
    void shouldGetRawDocumentById() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/documents/raw/" + createdDocumentId)
                .then()
                .statusCode(200)
                .body("data.id", equalTo(createdDocumentId))
                .body("data.status", equalTo("CREATED"))
                .body("data.origin", equalTo("XML_RAW"))
                .body("data.document_type", equalTo("01"))
                .body("data.issue_date", equalTo(today().toString()));
    }

    // ── 3. Idempotencia ──
    @Test
    @Order(3)
    void shouldReturnSameDocument_whenIdempotencyKeyRepeated() {
        var xml = buildValidInvoiceXml("000000099");

        var returnedId = RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "raw-idem-001")
                .header("X-Document-Type", "01")
                .contentType("application/xml")
                .body(xml)
                .when()
                .post("/v1/documents/raw")
                .then()
                .statusCode(202)
                .extract()
                .<String>path("data.id");

        org.junit.jupiter.api.Assertions.assertEquals(createdDocumentId, returnedId);
    }

    // ── 4. Documento duplicado ──
    @Test
    @Order(4)
    void shouldHandleDuplicateDocument() {
        var xml = buildValidInvoiceXml("000000001");

        var statusCode = RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "raw-idem-002-different")
                .header("X-Document-Type", "01")
                .contentType("application/xml")
                .body(xml)
                .when()
                .post("/v1/documents/raw")
                .then()
                .extract().statusCode();

        assertTrue(statusCode == 409 || statusCode == 202,
                "Expected 409 (duplicate) or 202 (recycled), got " + statusCode);
    }

    // ── 5. Header X-Document-Type faltante ──
    @Test
    @Order(5)
    void shouldReject_whenMissingDocumentTypeHeader() {
        var xml = buildValidInvoiceXml("000000010");

        RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "raw-idem-missing-type")
                .contentType("application/xml")
                .body(xml)
                .when()
                .post("/v1/documents/raw")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("MISSING_DOCUMENT_TYPE"));
    }

    // ── 6. Mismatch tipo de documento ──
    @Test
    @Order(6)
    void shouldReject_whenDocumentTypeMismatch() {
        var xml = buildValidInvoiceXml("000000011");

        RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "raw-idem-mismatch")
                .header("X-Document-Type", "04") // NC, but XML is factura 01
                .contentType("application/xml")
                .body(xml)
                .when()
                .post("/v1/documents/raw")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("DOCUMENT_TYPE_MISMATCH"));
    }

    // ── 7. XML inválido (malformado) ──
    @Test
    @Order(7)
    void shouldReject_whenXmlMalformed() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "raw-idem-malformed")
                .header("X-Document-Type", "01")
                .contentType("application/xml")
                .body("<factura><unclosed>")
                .when()
                .post("/v1/documents/raw")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("INVALID_XML_STRUCTURE"));
    }

    // ── 8. XML que no pasa validación XSD ──
    @Test
    @Order(8)
    void shouldReject_whenXsdValidationFails() {
        // Valid XML but missing required elements for factura XSD
        var xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <factura id="comprobante" version="2.1.0">
                  <infoTributaria>
                    <ambiente>1</ambiente>
                    <tipoEmision>1</tipoEmision>
                    <razonSocial>TEST</razonSocial>
                    <ruc>1790016919001</ruc>
                    <claveAcceso>0000000000000000000000000000000000000000000000000</claveAcceso>
                    <codDoc>01</codDoc>
                    <estab>001</estab>
                    <ptoEmi>001</ptoEmi>
                    <secuencial>000000099</secuencial>
                    <dirMatriz>Quito</dirMatriz>
                  </infoTributaria>
                </factura>
                """;

        RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "raw-idem-xsd-fail")
                .header("X-Document-Type", "01")
                .contentType("application/xml")
                .body(xml)
                .when()
                .post("/v1/documents/raw")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("XSD_VALIDATION_FAILED"));
    }

    // ── 9. Documento no encontrado ──
    @Test
    @Order(9)
    void shouldReturn404_whenDocumentNotFound() {
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/documents/raw/" + UUID.randomUUID())
                .then()
                .statusCode(404)
                .body("error.code", equalTo("DOCUMENT_NOT_FOUND"));
    }

    // ── 10. Email extraído de infoAdicional ──
    @Test
    @Order(10)
    void shouldExtractEmailFromInfoAdicional() {
        var xml = buildValidInvoiceXmlWithEmail("000000020", "test@example.com");

        var docId = RestAssured.given()
                .header("Authorization", authHeader())
                .header("X-Idempotency-Key", "raw-idem-email")
                .header("X-Document-Type", "01")
                .contentType("application/xml")
                .body(xml)
                .when()
                .post("/v1/documents/raw")
                .then()
                .statusCode(202)
                .extract()
                .<String>path("data.id");

        // Detail endpoint should show the email
        RestAssured.given()
                .header("Authorization", authHeader())
                .when()
                .get("/v1/documents/raw/" + docId)
                .then()
                .statusCode(200)
                .body("data.recipient.email", equalTo("test@example.com"));
    }

    // ── Helpers ──
    private String buildValidInvoiceXml(String sequenceNumber) {
        return buildValidInvoiceXmlWithEmail(sequenceNumber, null);
    }

    private String buildValidInvoiceXmlWithEmail(String sequenceNumber, String email) {
        var dateStr = todaySriFormat();
        var additionalInfo = "";
        if (email != null) {
            additionalInfo = """
                  <infoAdicional>
                    <campoAdicional nombre="Email">%s</campoAdicional>
                  </infoAdicional>
                """.formatted(email);
        }

        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <factura id="comprobante" version="2.1.0">
                  <infoTributaria>
                    <ambiente>1</ambiente>
                    <tipoEmision>1</tipoEmision>
                    <razonSocial>Raw E2E Test Corp S.A.</razonSocial>
                    <ruc>%s</ruc>
                    <claveAcceso>0000000000000000000000000000000000000000000000000</claveAcceso>
                    <codDoc>01</codDoc>
                    <estab>001</estab>
                    <ptoEmi>001</ptoEmi>
                    <secuencial>%s</secuencial>
                    <dirMatriz>Quito, Ecuador</dirMatriz>
                  </infoTributaria>
                  <infoFactura>
                    <fechaEmision>%s</fechaEmision>
                    <obligadoContabilidad>NO</obligadoContabilidad>
                    <tipoIdentificacionComprador>04</tipoIdentificacionComprador>
                    <razonSocialComprador>CLIENTE PRUEBA CIA. LTDA.</razonSocialComprador>
                    <identificacionComprador>1790016919001</identificacionComprador>
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
                      <precioUnitario>50.000000</precioUnitario>
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
                %s</factura>
                """.formatted(TENANT_RUC, sequenceNumber, dateStr, additionalInfo);
    }
}
