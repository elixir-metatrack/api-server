package no.metatrack.server.taxon;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TaxonResponse(
        @JsonProperty("name") String name,
        @JsonProperty("tax_id") String taxId) {}
