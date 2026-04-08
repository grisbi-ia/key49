package auracore.key49.core.tenant;

import java.util.function.Function;
import java.util.function.Supplier;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Ejecuta operaciones de base de datos dentro del esquema del tenant activo.
 * Aplica SET search_path antes de cada operación para garantizar aislamiento.
 */
@ApplicationScoped
public class TenantConnectionManager {

    @Inject
    EntityManager em;

    /**
     * Ejecuta una operación de lectura dentro del esquema del tenant.
     */
    @Transactional
    public <T> T withTenantSession(String schemaName, Function<EntityManager, T> work) {
        setSearchPath(schemaName);
        return work.apply(em);
    }

    /**
     * Ejecuta una operación transaccional dentro del esquema del tenant.
     */
    @Transactional
    public <T> T withTenantTransaction(String schemaName, Function<EntityManager, T> work) {
        setSearchPath(schemaName);
        return work.apply(em);
    }

    /**
     * Ejecuta una operación transaccional sin parámetro de sesión (Supplier).
     */
    @Transactional
    public <T> T withTenantTransaction(String schemaName, Supplier<T> work) {
        setSearchPath(schemaName);
        return work.get();
    }

    /**
     * Ejecuta una acción transaccional sin valor de retorno.
     */
    @Transactional
    public void runInTenantTransaction(String schemaName, Runnable work) {
        setSearchPath(schemaName);
        work.run();
    }

    /**
     * Establece el search_path del esquema del tenant.
     */
    public void setSearchPath(String schemaName) {
        var sql = TenantSchemaResolver.buildSearchPathSql(schemaName);
        Log.debugf("Setting search_path | schema=%s", schemaName);
        em.createNativeQuery(sql).executeUpdate();
    }
}
