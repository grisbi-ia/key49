package auracore.key49.api;

import java.net.URI;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * Redirige la raíz del dominio ({@code GET /}) al portal de login.
 *
 * <p>Evita que un visitante casual vea un 404 JSON al acceder
 * a {@code https://key49.apx5.com/} desde el navegador.</p>
 */
@Path("/")
public class RootRedirectResource {

    @GET
    public Response redirectToPortal() {
        return Response.seeOther(URI.create("/portal/login")).build();
    }
}
