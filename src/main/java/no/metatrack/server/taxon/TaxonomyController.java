package no.metatrack.server.taxon;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/taxon")
@Produces(MediaType.APPLICATION_JSON)
public class TaxonomyController {

    @Inject
    TaxonomyService taxonomyService;

    @GET
    @Path("/{tax_id}")
    public TaxonResponse getTaxon(@PathParam("tax_id") String taxId) {
        return taxonomyService.getTaxon(taxId);
    }
}
