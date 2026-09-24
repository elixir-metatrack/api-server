package no.metatrack.server.assay;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.file.File;
import no.metatrack.server.sample.Sample;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class CSVExperimentRowWriter {
    @Inject
    AssayService assayService;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void write(Long projectId, UUID assayId, String sampleName, String instrumentModel, String libraryName,
            String librarySource, String librarySelection, String libraryStrategy, String libraryLayout,
            Integer insertSize, String sequencingPlatform, String sequencingLaboratory,
            List<CSVExperimentImportService.PendingFile> pendingFiles) {
        assayService.updateAssay(assayId, null, null, instrumentModel, libraryName, librarySource,
                librarySelection, libraryStrategy, libraryLayout, insertSize);
        Assay assay = assayService.getAssayById(assayId);
        Sample sample = Sample.findBySampleNameInProject(sampleName, projectId).orElseThrow(NotFoundException::new);
        if (sequencingPlatform != null) assay.sequencingPlatform = sequencingPlatform;
        if (sequencingLaboratory != null) assay.sequencingLaboratory = sequencingLaboratory;
        assayService.associateSample(assay, sample);
        for (var pendingFile : pendingFiles) {
            File.importPending(projectId, assayId, sample, assay, pendingFile.fileName(), pendingFile.md5(),
                    pendingFile.unencryptedMd5()).ifPresent(message -> {
                throw new FileConflictException(pendingFile.field(), pendingFile.fileName(), message);
            });
        }
    }

    static class FileConflictException extends RuntimeException {
        private final String field;
        private final String fileName;

        FileConflictException(String field, String fileName, String message) {
            super(message);
            this.field = field;
            this.fileName = fileName;
        }

        String field() { return field; }

        String fileName() { return fileName; }
    }
}