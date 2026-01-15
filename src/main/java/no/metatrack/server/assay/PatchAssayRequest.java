package no.metatrack.server.assay;

import jakarta.validation.constraints.Positive;

public record PatchAssayRequest(
        String name,
        String studyAccession,
        String instrumentModel,
        String libraryName,
        String librarySource,
        String librarySelection,
        String libraryLayout,

        @Positive(message = "Insert size can not be zero or negative")
        Integer insertSize) {}
