package no.metatrack.server.assay;

import java.time.Instant;
import java.util.UUID;

public record AssayResponse(
        UUID id,
        String name,
        String studyAccession,
        String instrumentModel,
        String libraryName,
        String librarySource,
        String librarySelection,
        String libraryStrategy,
        String libraryLayout,
        Integer insertSize,
        Instant createdOn,
        Instant modifiedOn) {

    public static AssayResponse fromEntity(Assay assay) {
        return new AssayResponse(
                assay.id,
                assay.name,
                assay.studyAccession,
                assay.instrumentModel,
                assay.libraryName,
                assay.librarySource,
                assay.librarySelection,
                assay.libraryStrategy,
                assay.libraryLayout,
                assay.insertSize,
                assay.createdOn,
                assay.modifiedOn);
    }
}
