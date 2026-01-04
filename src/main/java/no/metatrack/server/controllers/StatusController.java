package no.metatrack.server.controllers;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import no.metatrack.server.Dtos.HealthStatusResponse;

@Path("/")
public class StatusController {
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public HealthStatusResponse status() {
        return new HealthStatusResponse("OK");
    }
}
