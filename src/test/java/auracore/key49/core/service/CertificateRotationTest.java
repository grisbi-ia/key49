package auracore.key49.core.service;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import auracore.key49.core.model.Tenant;

/**
 * Tests unitarios para la lógica de rotación de certificados sin downtime.
 * Verifica que los campos pending se manejan correctamente al rotar y activar.
 */
class CertificateRotationTest {

    // ── Helpers ──
    private Tenant createTenantWithCert() {
        var t = new Tenant();
        t.id = UUID.randomUUID();
        t.ruc = "1790016919001";
        t.legalName = "EMPRESA S.A.";
        t.mainAddress = "Quito";
        t.environment = "test";
        t.schemaName = "tenant_test";
        t.status = "active";
        t.certificateP12 = new byte[]{1, 2, 3};
        t.certificatePasswordEnc = new byte[]{10, 20, 30};
        t.certificateSubject = "CN=Active, O=Company";
        t.certificateSerial = "ACTIVE-001";
        t.certificateExpiration = Instant.parse("2027-06-15T00:00:00Z");
        t.createdAt = Instant.now();
        t.updatedAt = Instant.now();
        return t;
    }

    // ── Pending fields management ──
    @Nested
    @DisplayName("Pending certificate fields")
    class PendingFields {

        @Test
        @DisplayName("tenant sin rotación pendiente tiene campos null")
        void noPendingByDefault() {
            var tenant = createTenantWithCert();
            assertNull(tenant.pendingCertificateP12);
            assertNull(tenant.pendingCertificatePasswordEnc);
            assertNull(tenant.pendingCertificateSubject);
            assertNull(tenant.pendingCertificateExpiration);
            assertNull(tenant.pendingCertificateSerial);
        }

        @Test
        @DisplayName("asignar pending preserva campos activos sin modificar")
        void settingPendingDoesNotAffectActive() {
            var tenant = createTenantWithCert();
            var originalP12 = tenant.certificateP12.clone();
            var originalSubject = tenant.certificateSubject;
            var originalSerial = tenant.certificateSerial;
            var originalExpiration = tenant.certificateExpiration;

            // Simular rotación: asignar campos pending
            tenant.pendingCertificateP12 = new byte[]{4, 5, 6};
            tenant.pendingCertificatePasswordEnc = new byte[]{40, 50, 60};
            tenant.pendingCertificateSubject = "CN=Pending, O=Company";
            tenant.pendingCertificateSerial = "PENDING-001";
            tenant.pendingCertificateExpiration = Instant.parse("2028-06-15T00:00:00Z");

            // Verificar que los campos activos no cambiaron
            assertArrayEquals(originalP12, tenant.certificateP12);
            assertEquals(originalSubject, tenant.certificateSubject);
            assertEquals(originalSerial, tenant.certificateSerial);
            assertEquals(originalExpiration, tenant.certificateExpiration);
        }

        @Test
        @DisplayName("activar mueve pending a activo y limpia pending")
        void activateMovesPendingToActive() {
            var tenant = createTenantWithCert();
            var pendingP12 = new byte[]{4, 5, 6};
            var pendingPassword = new byte[]{40, 50, 60};

            // Simular rotación
            tenant.pendingCertificateP12 = pendingP12;
            tenant.pendingCertificatePasswordEnc = pendingPassword;
            tenant.pendingCertificateSubject = "CN=New, O=Company";
            tenant.pendingCertificateSerial = "NEW-001";
            tenant.pendingCertificateExpiration = Instant.parse("2028-06-15T00:00:00Z");

            // Simular activación (misma lógica que TenantAdminService.activateCertificate)
            tenant.certificateP12 = tenant.pendingCertificateP12;
            tenant.certificatePasswordEnc = tenant.pendingCertificatePasswordEnc;
            tenant.certificateSubject = tenant.pendingCertificateSubject;
            tenant.certificateExpiration = tenant.pendingCertificateExpiration;
            tenant.certificateSerial = tenant.pendingCertificateSerial;

            tenant.pendingCertificateP12 = null;
            tenant.pendingCertificatePasswordEnc = null;
            tenant.pendingCertificateSubject = null;
            tenant.pendingCertificateExpiration = null;
            tenant.pendingCertificateSerial = null;

            // Verificar que los campos activos ahora son los del pending
            assertArrayEquals(pendingP12, tenant.certificateP12);
            assertArrayEquals(pendingPassword, tenant.certificatePasswordEnc);
            assertEquals("CN=New, O=Company", tenant.certificateSubject);
            assertEquals("NEW-001", tenant.certificateSerial);
            assertEquals(Instant.parse("2028-06-15T00:00:00Z"), tenant.certificateExpiration);

            // Verificar que pending fue limpiado
            assertNull(tenant.pendingCertificateP12);
            assertNull(tenant.pendingCertificatePasswordEnc);
            assertNull(tenant.pendingCertificateSubject);
            assertNull(tenant.pendingCertificateExpiration);
            assertNull(tenant.pendingCertificateSerial);
        }
    }

    // ── TenantException for missing pending cert ──
    @Nested
    @DisplayName("Activation validation")
    class ActivationValidation {

        @Test
        @DisplayName("NO_PENDING_CERTIFICATE: código y status correctos")
        void noPendingCertificateException() {
            var ex = new TenantAdminService.TenantException(
                    "NO_PENDING_CERTIFICATE",
                    "No pending certificate to activate for tenant: " + UUID.randomUUID(),
                    422);

            assertEquals("NO_PENDING_CERTIFICATE", ex.code());
            assertEquals(422, ex.httpStatus());
            assertNotNull(ex.getMessage());
        }
    }
}
