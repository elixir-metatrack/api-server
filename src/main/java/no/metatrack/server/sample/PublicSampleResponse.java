package no.metatrack.server.sample;

import java.util.UUID;

public record PublicSampleResponse(
        UUID id,
        Integer taxId,
        String scientificName,
        String dataOwningOrganization) {}