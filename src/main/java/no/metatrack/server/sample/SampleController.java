package no.metatrack.server.sample;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.file.File;
import no.metatrack.server.file.FileIngestService;
import no.metatrack.server.file.FileResponse;
import no.metatrack.server.file.FileService;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import no.metatrack.server.sample.vocabulary.SampleValidationViolation;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Path("/api/projects/{projectId}/samples")
public class SampleController {
    private static final Set<String> ALLOWED_SAMPLESHEET_TYPES = Set.of(
            "text/csv",
            "text/plain",
            "text/tab-separated-values",
            "text/tsv",
            "application/vnd.ms-excel"
    );
    @Inject
    SampleService sampleService;

    @Inject
    FileService fileService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @Inject
    CSVSampleSheetImportService csvSampleSheetImportService;

    @Inject
    FileIngestService fileIngestService;

    @Inject
    SampleMetadataService metadataService;

    @GET
    @Authenticated
    public List<SampleResponse> getAllSamples(@PathParam("projectId") Long projectId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();
        List<Sample> samples = sampleService.getAllSamples(projectId);
        Map<UUID, Map<String, Object>> metadata = metadataService.getActiveMetadata(samples);

        return samples.stream()
                .map(sample -> SampleResponse.fromEntity(sample, metadata.get(sample.id)))
                .toList();
    }

    @GET
    @Path("/{sampleId}")
    @Authenticated
    public SampleResponse getSample(@PathParam("projectId") Long projectId, @PathParam("sampleId") UUID sampleId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();

        Sample sample = sampleService.getSampleById(sampleId, projectId);
        return SampleResponse.fromEntity(sample, metadataService.getActiveMetadata(sample));
    }

    @GET
    @Path("/name/{sampleName}")
    @Authenticated
    public SampleResponse getSampleByName(
            @PathParam("projectId") Long projectId, @PathParam("sampleName") String sampleName) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();

        Sample sample = sampleService.getSampleByName(sampleName, projectId);

        return SampleResponse.fromEntity(sample, metadataService.getActiveMetadata(sample));
    }

    @POST
    @Authenticated
    public SampleResponse createSample(@PathParam("projectId") Long projectId, @Valid CreateSampleRequest request) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();

        Sample sample = sampleService.createSample(
                projectId,
                request.name(),
                request.alias(),
                request.taxId(),
                request.hostTaxId(),
                request.mlst(),
                request.location(),
                request.sequencingLab(),
                request.institution(),
                request.isolationSource(),
                request.collectionDate(),
                request.hostHealthState(),
                request.projectTitle(),
                request.description(),
                request.isolate(),
                request.collectedBy(),
                request.latitude(),
                request.longitude(),
                request.environmentalSample(),
                request.hostAssociated(),
                request.hostCommonName(),
                request.hostSubjectId(),
                request.collectorName(),
                request.collectingInstitution(),
                request.hostSex(),
                request.influenzaTestMethod(),
                request.influenzaTestResult(),
                request.otherPathogensTested(),
                request.otherPathogensTestResult(),
                request.hostHabitat(),
                request.isolationSourceHostAssociated(),
                request.hostBehaviour(),
                request.isolationSourceNonHostAssociated(),
                request.influenzaVirusType(),
                request.influenzaSubType(),
                request.serovar(),
                request.strain(),
                request.hostAge(),
                request.county(),
                request.commune(),
                request.hospitalHealthInstitution(),
                request.customMetadata());

        return SampleResponse.fromEntity(sample, metadataService.getActiveMetadata(sample));
    }

    @PATCH
    @Authenticated
    @Path("/{sampleId}")
    public Response updateSample(
            @PathParam("projectId") Long projectId,
            @PathParam("sampleId") UUID sampleId,
            @Valid PatchSampleRequest request) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();

        sampleService.updateSample(
                projectId,
                sampleId,
                request.name(),
                request.alias(),
                request.taxId(),
                request.hostTaxId(),
                request.mlst(),
                request.location(),
                request.sequencingLab(),
                request.institution(),
                request.isolationSource(),
                request.collectionDate(),
                request.hostHealthState(),
                request.projectTitle(),
                request.description(),
                request.isolate(),
                request.collectedBy(),
                request.latitude(),
                request.longitude(),
                request.environmentalSample(),
                request.hostAssociated(),
                request.hostCommonName(),
                request.hostSubjectId(),
                request.collectorName(),
                request.collectingInstitution(),
                request.hostSex(),
                request.influenzaTestMethod(),
                request.influenzaTestResult(),
                request.otherPathogensTested(),
                request.otherPathogensTestResult(),
                request.hostHabitat(),
                request.isolationSourceHostAssociated(),
                request.hostBehaviour(),
                request.isolationSourceNonHostAssociated(),
                request.influenzaVirusType(),
                request.influenzaSubType(),
                request.serovar(),
                request.strain(),
                request.hostAge(),
                request.county(),
                request.commune(),
                request.hospitalHealthInstitution(),
                request.customMetadata());

        return Response.noContent().build();
    }

    @DELETE
    @Authenticated
    @Path("/{sampleId}")
    public Response deleteSample(@PathParam("projectId") Long projectId, @PathParam("sampleId") UUID sampleId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();

        sampleService.deleteSample(projectId, sampleId);
        return Response.noContent().build();
    }

    @POST
    @Authenticated
    @Path("/samplesheet")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response importCSV(@PathParam("projectId") Long projectId, @RestForm("file") FileUpload file) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();

        if (file == null) throw new BadRequestException("No file uploaded");

        String contentType = file.contentType();
        String baseContentType = contentType != null ? contentType.split(";")[0].trim().toLowerCase() : null;

        if (baseContentType == null || !ALLOWED_SAMPLESHEET_TYPES.contains(baseContentType)) {
            throw new WebApplicationException("File must be a CSV or TSV file", 400);
        }

        List<SampleValidationViolation> errors = csvSampleSheetImportService.importNewSamples(
                projectId, file.filePath().toFile());

        if (errors == null || errors.isEmpty()) {
            return Response.ok().build();
        }

        return Response.status(400).entity(errors).build();
    }

    @PATCH
    @Authenticated
    public Response batchUpdateSamples(@PathParam("projectId") Long projectId, @Valid BulkPatchSampleRequest request) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();

        List<SampleValidationViolation> errors = sampleService.bulkPatchSamples(projectId, request);

        if (errors == null || errors.isEmpty()) {
            return Response.ok().build();
        }

        return Response.status(400).entity(errors).build();
    }

    @GET
    @Authenticated
    @Path("/{sampleId}/files")
    public List<FileResponse> getAllFilesInSample(
            @PathParam("projectId") Long projectId, @PathParam("sampleId") UUID sampleId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();

        List<File> files = fileService.getAllFilesInSample(projectId, sampleId);
        return files.stream().map(FileResponse::fromEntity).toList();
    }

    @DELETE
    @Authenticated
    @Path("/{sampleId}/files/{fileUuid}")
    public Response deleteFile(
            @PathParam("projectId") Long projectId,
            @PathParam("sampleId") UUID sampleId,
            @PathParam("fileUuid") UUID fileUuid) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();

        fileIngestService.deleteFile(fileUuid, sampleId);
        return Response.noContent().build();
    }

    @PUT
    @Authenticated
    @Path("/link")
    public Response linkSamples(@PathParam("projectId") Long projectId, @Valid LinkSamplesRequest request) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();

        sampleService.linkSamples(projectId, request.sampleIds());
        return Response.noContent().build();
    }

    @DELETE
    @Authenticated
    @Path("/link")
    public Response unlinkSamples(@PathParam("projectId") Long projectId, @Valid LinkSamplesRequest request) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();

        sampleService.unlinkSamples(projectId, request.sampleIds());
        return Response.noContent().build();
    }
}
