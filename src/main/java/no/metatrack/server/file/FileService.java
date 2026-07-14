package no.metatrack.server.file;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.NotFoundException;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.sample.Sample;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class FileService {
    public List<File> getAllFilesInSample(Long projectId, UUID sampleId) {
        if (Sample.count("id = ?1 and project.id = ?2", sampleId, projectId) == 0) throw new NotFoundException();
        return File.findInSample(projectId, sampleId);
    }

    public List<File> getAllFilesInAssay(Long projectId, UUID assayId) {
        if (!Assay.existsAssayByIdInProjectOptional(projectId, assayId)) throw new NotFoundException();
        return File.findInAssay(projectId, assayId);
    }

    public List<File> getFilesInSampleAndAssay(Long projectId, UUID assayId, UUID sampleId) {
        Assay.find(
                        "select a from Assay a join a.samples s "
                                + "where a.id = ?1 and a.project.id = ?2 and s.id = ?3 and s.project.id = ?2",
                        assayId, projectId, sampleId)
                .firstResultOptional()
                .orElseThrow(NotFoundException::new);
        return File.findInSampleAndAssay(projectId, sampleId, assayId);
    }
}