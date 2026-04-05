package auracore.key49.core.tenant;

import io.quarkus.logging.Log;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.hibernate.reactive.mutiny.Mutiny;

import java.util.function.Function;

/**
 * Ejecuta operaciones de base de datos dentro del esquema del tenant activo.
 * Aplica SET search_path antes de cada operación para garantizar aislamiento.
 */
@ApplicationScoped
public class TenantConnectionManager {

    @Inject
    Mutiny.SessionFactory sessionFactory;

    /**
     * Ejecuta una operación de lectura dentro del esquema del tenant.
     */
    public <T> Uni<T> withTenantSession(String schemaName, Function<Mutiny.Session, Uni<T>> work) {
        var sql = TenantSchemaResolver.buildSearchPathSql(schemaName);
        Log.debugf("Setting search_path | schema=%s", schemaName);

        return sessionFactory.withSession(session ->
                session.createNativeQuery(sql)
                        .executeUpdate()
                        .chain(() -> work.apply(session))
        );
    }

    /**
     * Ejecuta una operación transaccional dentro del esquema del tenant.
     */
    public <T> Uni<T> withTenantTransaction(String schemaName, Function<Mutiny.Session, Uni<T>> work) {
        var sql = TenantSchemaResolver.buildSearchPathSql(schemaName);
        Log.debugf("Setting search_path (tx) | schema=%s", schemaName);

        return sessionFactory.withTransaction((session, tx) ->
                session.createNativeQuery(sql)
                        .executeUpdate()
                        .chain(() -> work.apply(session))
        );
    }
}
