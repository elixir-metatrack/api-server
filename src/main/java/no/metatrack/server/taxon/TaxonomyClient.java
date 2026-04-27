package no.metatrack.server.taxon;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

@Path("/taxon")
@RegisterRestClient(configKey = "taxon-api")
public interface TaxonomyClient {

    @GET
    @Path("/{tax_id}")
    TaxonResponse getTaxon(@PathParam("tax_id") String taxId);
}
