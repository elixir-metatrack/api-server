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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Path("/api/public/samples")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Public sample catalog", description = "Unauthenticated summary access to cataloged samples")
public class PublicSampleController {
    @Inject
    PublicSampleService publicSampleService;

    @GET
    @PermitAll
    @Operation(summary = "List public samples", description = "No authentication required. Returns 100 samples per zero-based page in UUID order, with a limited public metadata projection.")
    @APIResponse(responseCode = "400", description = "Page must be zero or greater")
    public PublicSamplePage getSamples(
            @Parameter(description = "Zero-based page index", example = "0")
            @QueryParam("page") @DefaultValue("0") @Min(0) int page) {
        if (page < 0) throw new BadRequestException("Page must be zero or greater");
        return publicSampleService.getPage(page);
    }

    @GET
    @Path("/species")
    @PermitAll
    @Operation(summary = "List public sample taxon IDs", description = "No authentication required. Returns sorted, distinct sample taxon IDs; host taxon IDs are not included.")
    public List<Integer> getSpecies() {
        return publicSampleService.getSpecies();
    }

    @GET
    @Path("/organizations")
    @PermitAll
    @Operation(summary = "List public sample organizations", description = "No authentication required. Returns sorted, distinct non-blank data-owning organizations for projects with samples.")
    public List<String> getOrganizations() {
        return publicSampleService.getOrganizations();
    }
}