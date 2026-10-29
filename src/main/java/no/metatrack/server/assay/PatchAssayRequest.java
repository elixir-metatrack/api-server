package no.metatrack.server.assay;

import jakarta.validation.constraints.Positive;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(description = "Partial assay update. Only non-null fields are applied; insert size, when supplied, must be positive.")
public record PatchAssayRequest(
        String name,
        String studyAccession,
        String instrumentModel,
        String libraryName,
        String librarySource,
        String libraryStrategy,
        String librarySelection,
        String libraryLayout,

        @Positive(message = "Insert size can not be zero or negative")
        Integer insertSize,
        String experimentAccession,
        String runAccession) {}
