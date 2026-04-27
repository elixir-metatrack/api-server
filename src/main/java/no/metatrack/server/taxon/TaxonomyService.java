package no.metatrack.server.taxon;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;

@ApplicationScoped
public class TaxonomyService {

    @RestClient
    TaxonomyClient taxonomyClient;

    public TaxonResponse getTaxon(String taxId) {
        return taxonomyClient.getTaxon(taxId);
    }
}
