package no.metatrack.server.assay;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.MediaType;
import no.metatrack.server.project.Project;
import no.metatrack.server.file.File;
import no.metatrack.server.file.FileResponse;
import no.metatrack.server.file.FileService;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import no.metatrack.server.sample.Sample;
import no.metatrack.server.sample.SampleResponse;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

@Path("/api/projects/{projectId}/assays")
public class AssayController {
    private static final Set<String> ALLOWED_EXPERIMENT_TYPES = Set.of(
            "text/csv", "text/plain", "text/tab-separated-values", "text/tsv", "application/vnd.ms-excel");
    @Inject
    SampleMetadataService metadataService;
    @Inject
    AssayService assayService;

    @Inject
    FileService fileService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @Inject
    CSVExperimentImportService csvExperimentImportService;

    @GET
    @Authenticated
    public List<AssayResponse> getAllAssaysInProject(@PathParam("projectId") Long projectId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        List<Assay> assays = assayService.getAllAssaysInProject(projectId);

        return assays.stream().map(AssayResponse::fromEntity).toList();
    }

    @GET
    @Authenticated
    @Path("/{assayId}")
    public AssayResponse getAssayById(@PathParam("projectId") Long projectId, @PathParam("assayId") UUID assayId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        Assay assay = assayService.getAssayById(assayId);
        return AssayResponse.fromEntity(assay);
    }

    @POST
    @Authenticated
    public AssayResponse createNewAssay(@PathParam("projectId") Long projectId, CreateAssayRequest request) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        Assay assay = assayService.createAssay(
                projectId,
                request.name(),
                request.studyAccession(),
                request.instrumentModel(),
                request.libraryName(),
                request.librarySource(),
                request.libraryStrategy(),
                request.librarySelection(),
                request.libraryLayout(),
                request.insertSize());

        return AssayResponse.fromEntity(assay);
    }

    @PATCH
    @Authenticated
    @Path("/{assayId}")
    public Response updateAssay(
            @PathParam("projectId") Long projectId, @PathParam("assayId") UUID assayId, PatchAssayRequest request) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        assayService.updateAssay(
                assayId,
                request.name(),
                request.studyAccession(),
                request.instrumentModel(),
                request.libraryName(),
                request.librarySource(),
                request.librarySelection(),
                request.libraryStrategy(),
                request.libraryLayout(),
                request.insertSize());

        return Response.noContent().build();
    }

    @POST
    @Authenticated
    @Path("/{assayId}/experiments")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response importExperiments(
            @PathParam("projectId") Long projectId,
            @PathParam("assayId") UUID assayId,
            @RestForm("file") FileUpload file) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();
        if (file == null) throw new BadRequestException("No file uploaded");

        String contentType = file.contentType();
        String baseContentType = contentType == null ? null : contentType.split(";")[0].trim().toLowerCase();
        if (baseContentType == null || !ALLOWED_EXPERIMENT_TYPES.contains(baseContentType)) {
            throw new WebApplicationException("File must be a CSV or TSV file", 400);
        }

        List<CSVExperimentRowError> errors = csvExperimentImportService.importIntoAssay(
                projectId, assayId, file.filePath().toFile());
        return errors.isEmpty() ? Response.ok().build() : Response.status(400).entity(errors).build();
    }

    @DELETE
    @Authenticated
    @Path("/{assayId}")
    public Response deleteAssay(@PathParam("projectId") Long projectId, @PathParam("assayId") UUID assayId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        assayService.deleteAssay(assayId);
        return Response.noContent().build();
    }

    @PUT
    @Authenticated
    @Path("/{assayId}/samples")
    public List<String> addSamplesToAssay(
            @PathParam("projectId") Long projectId,
            @PathParam("assayId") UUID assayId,
            AddRemoveSamplesFromAssayRequest request) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        return assayService.addSamplesToAssay(projectId, request.sampleNames(), assayId);
    }

    @DELETE
    @Authenticated
    @Path("/{assayId}/samples")
    public List<String> removeSamplesFromAssay(
            @PathParam("projectId") Long projectId,
            @PathParam("assayId") UUID assayId,
            AddRemoveSamplesFromAssayRequest request) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        return assayService.removeSamplesFromAssay(projectId, request.sampleNames(), assayId);
    }

    @GET
    @Authenticated
    @Path("/{assayId}/samples")
    public List<SampleResponse> getSamplesInAssay(
            @PathParam("projectId") Long projectId, @PathParam("assayId") UUID assayId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER))
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        if (!Assay.existsAssayByIdInProjectOptional(projectId, assayId)) throw new NotFoundException("Assay not found");

        List<Sample> samples = assayService.getAllSamplesInAssay(assayId);
        Map<UUID, Map<String, Object>> metadata = metadataService.getActiveMetadata(samples);

        return samples.stream()
                .map(sample -> SampleResponse.fromEntity(sample, metadata.get(sample.id)))
                .toList();
    }

    @GET
    @Authenticated
    @Path("/{assayId}/files")
    public List<FileResponse> getAllFilesInAssay(
            @PathParam("projectId") Long projectId, @PathParam("assayId") UUID assayId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();

        List<File> files = fileService.getAllFilesInAssay(projectId, assayId);
        return files.stream().map(FileResponse::fromEntity).toList();
    }

    @GET
    @Authenticated
    @Path("/{assayId}/samples/{sampleId}/files")
    public List<FileResponse> getFilesInSampleAndAssay(
            @PathParam("projectId") Long projectId,
            @PathParam("assayId") UUID assayId,
            @PathParam("sampleId") UUID sampleId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();

        return fileService.getFilesInSampleAndAssay(projectId, assayId, sampleId).stream()
                .map(FileResponse::fromEntity)
                .toList();
    }
}
