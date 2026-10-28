package no.metatrack.server.taxon;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/taxon")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Taxonomy", description = "Taxon lookup by external taxonomy identifier")
public class TaxonomyController {

    @Inject
    TaxonomyService taxonomyService;

    @GET
    @Path("/{tax_id}")
    @Operation(summary = "Look up a taxon", description = "Resolves the supplied taxonomy ID through the configured taxonomy service; no authentication is required.")
    @APIResponse(responseCode = "404", description = "Taxon not found")
    public TaxonResponse getTaxon(@PathParam("tax_id") String taxId) {
        return taxonomyService.getTaxon(taxId);
    }
}
