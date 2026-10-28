package no.metatrack.server.sample;

import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/public/samples")
@Produces(MediaType.APPLICATION_JSON)
public class PublicSampleController {
    @Inject
    PublicSampleService publicSampleService;

    @GET
    @PermitAll
    public PublicSamplePage getSamples(@QueryParam("page") @DefaultValue("0") @Min(0) int page) {
        if (page < 0) throw new BadRequestException("Page must be zero or greater");
        return publicSampleService.getPage(page);
    }

    @GET
    @Path("/species")
    @PermitAll
    public List<Integer> getSpecies() {
        return publicSampleService.getSpecies();
    }

    @GET
    @Path("/organizations")
    @PermitAll
    public List<String> getOrganizations() {
        return publicSampleService.getOrganizations();
    }
}