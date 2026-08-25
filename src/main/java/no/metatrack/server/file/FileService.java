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
        if (!Assay.existsAssayByIdInProjectOptional(projectId, assayId)) throw new NotFoundException();
        // Files are indexed by the assay's actual (root) project, which may differ from
        // the sub-project id the request came in through.
        Assay assay = (Assay) Assay.findByIdOptional(assayId).orElseThrow(NotFoundException::new);
        return File.findInAssay(assay.project.id, assayId);
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