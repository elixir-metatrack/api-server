package no.metatrack.server.controllers;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import no.metatrack.server.Dtos.HealthStatusResponse;
import org.jboss.logging.Logger;

@Path("/")
public class StatusController {
    private static final Logger log = Logger.getLogger(StatusController.class);

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public HealthStatusResponse status() {
        log.info("status:ok");
        return new HealthStatusResponse("OK");
    }
}
