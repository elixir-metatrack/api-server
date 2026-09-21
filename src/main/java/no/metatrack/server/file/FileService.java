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
        Sample sample = Sample.findByIdInProjectScope(sampleId, projectId).orElseThrow(NotFoundException::new);
        return File.findInSample(sample.project.id, sampleId);
    }

    public List<File> getAllFilesInAssay(Long projectId, UUID assayId) {
        Assay assay = Assay.findByIdInProjectScope(projectId, assayId).orElseThrow(NotFoundException::new);
        return File.findInAssayInProjectScope(projectId, assay);
    }

    public List<File> getFilesInSampleAndAssay(Long projectId, UUID assayId, UUID sampleId) {
        Sample sample = Sample.findByIdInProjectScope(sampleId, projectId).orElseThrow(NotFoundException::new);
        if (!Assay.existsAssayByIdInProjectOptional(projectId, assayId)) throw new NotFoundException();
        Assay.find("select a from Assay a join a.samples s where a.id = ?1 and s.id = ?2", assayId, sampleId)
                .firstResultOptional()
                .orElseThrow(NotFoundException::new);
        return File.findInSampleAndAssay(sample.project.id, sampleId, assayId);
    }
}
