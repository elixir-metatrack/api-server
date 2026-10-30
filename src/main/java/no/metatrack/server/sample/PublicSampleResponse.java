package no.metatrack.server.sample;

import java.util.UUID;

public record PublicSampleResponse(
        UUID id,
        Integer taxId,
        String scientificName,
        String dataOwningOrganization,
        Integer hostTaxId,
        String hostScientificName,
        Integer collectionYear,
        String isolationSource,
        String sampleAccession) {}