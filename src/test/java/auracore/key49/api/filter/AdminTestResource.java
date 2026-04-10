package auracore.key49.api.filter;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * Recurso de prueba para validar el filtro de administración. Solo existe en
 * test classpath.
 */
@Path("/v1/admin/test")
public class AdminTestResource {

    @GET
    public String ping() {
        return "admin-ok";
    }
}
