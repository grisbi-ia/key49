package auracore.key49.api.model;

import auracore.key49.core.model.Tenant;
import auracore.key49.core.repository.TenantRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class TenantCrudTest {

    @Inject
    Mutiny.SessionFactory sessionFactory;

    @Inject
    TenantRepository tenantRepository;

    @Test
    void shouldPersistAndFindTenant() {
        var tenant = new Tenant();
        tenant.ruc = "1790016919001";
        tenant.legalName = "Empresa Test S.A.";
        tenant.mainAddress = "Quito, Ecuador";
        tenant.schemaName = "tenant_test_crud";
        tenant.createdAt = Instant.now();
        tenant.updatedAt = Instant.now();

        var persisted = sessionFactory.withTransaction(session ->
                session.persist(tenant).replaceWith(tenant)
        ).await().indefinitely();

        assertNotNull(persisted.id);

        var found = sessionFactory.withSession(session ->
                tenantRepository.findByRuc("1790016919001")
        ).await().indefinitely();

        assertNotNull(found);
        assertEquals("Empresa Test S.A.", found.legalName);
        assertEquals("tenant_test_crud", found.schemaName);
        assertEquals("active", found.status);
    }

    @Test
    void shouldFindTenantBySchemaName() {
        var tenant = new Tenant();
        tenant.ruc = "0992877878001";
        tenant.legalName = "Otra Empresa S.A.";
        tenant.mainAddress = "Guayaquil, Ecuador";
        tenant.schemaName = "tenant_otra";
        tenant.createdAt = Instant.now();
        tenant.updatedAt = Instant.now();

        sessionFactory.withTransaction(session ->
                session.persist(tenant).replaceWith(tenant)
        ).await().indefinitely();

        var found = sessionFactory.withSession(session ->
                tenantRepository.findBySchemaName("tenant_otra")
        ).await().indefinitely();

        assertNotNull(found);
        assertEquals("0992877878001", found.ruc);
    }
}
