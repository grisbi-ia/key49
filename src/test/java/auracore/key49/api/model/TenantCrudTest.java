package auracore.key49.api.model;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;

import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.TenantRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

@QuarkusTest
class TenantCrudTest {

    @Inject
    EntityManager em;

    @Inject
    TenantRepository tenantRepository;

    @Test
    @Transactional
    void shouldPersistAndFindTenant() {
        var tenant = new Tenant();
        tenant.ruc = "1790016919001";
        tenant.legalName = "Empresa Test S.A.";
        tenant.mainAddress = "Quito, Ecuador";
        tenant.schemaName = "tenant_test_crud";
        tenant.createdAt = Instant.now();
        tenant.updatedAt = Instant.now();

        em.persist(tenant);
        em.flush();

        assertNotNull(tenant.id);

        var found = tenantRepository.findByRuc("1790016919001");

        assertNotNull(found);
        assertEquals("Empresa Test S.A.", found.legalName);
        assertEquals("tenant_test_crud", found.schemaName);
        assertEquals("active", found.status);
    }

    @Test
    @Transactional
    void shouldFindTenantBySchemaName() {
        var tenant = new Tenant();
        tenant.ruc = "0992877878001";
        tenant.legalName = "Otra Empresa S.A.";
        tenant.mainAddress = "Guayaquil, Ecuador";
        tenant.schemaName = "tenant_otra";
        tenant.createdAt = Instant.now();
        tenant.updatedAt = Instant.now();

        em.persist(tenant);
        em.flush();

        var found = tenantRepository.findBySchemaName("tenant_otra");

        assertNotNull(found);
        assertEquals("0992877878001", found.ruc);
    }
}
