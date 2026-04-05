package auracore.key49.api.filter;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/**
 * Recurso de prueba para validar el filtro de autenticación.
 * Solo existe en test classpath.
 */
@Path("/auth-test")
public class AuthTestResource {

    @GET
    public String ping() {
        return "ok";
    }
}
