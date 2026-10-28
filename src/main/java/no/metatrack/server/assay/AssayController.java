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
import no.metatrack.server.spreadsheet.TableUploadSupport;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/projects/{projectId}/assays")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Assays", description = "Project-scoped assays, sample associations, experiment imports, and files")
public class AssayController {

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
    @Operation(summary = "List project assays", description = "Requires VIEWER membership in the project.")
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
    @Operation(summary = "Get an assay", description = "Requires VIEWER membership in the project.")
    @APIResponse(responseCode = "404", description = "Project or project-scoped assay not found")
    public AssayResponse getAssayById(@PathParam("projectId") Long projectId, @PathParam("assayId") UUID assayId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        Assay assay = assayService.getAssayById(projectId, assayId);
        return AssayResponse.fromEntity(assay);
    }

    @POST
    @Authenticated
    @Operation(summary = "Create an assay", description = "Requires EDITOR membership in the project. Assays are created in the parent project, not in a sub-project; the response is the created assay.")
    @APIResponse(responseCode = "403", description = "Project EDITOR membership required, or assay creation was attempted in a sub-project")
    @APIResponse(responseCode = "404", description = "Project not found")
    public AssayResponse createNewAssay(
            @PathParam("projectId") Long projectId,
            @RequestBody(description = "Assay fields", content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = CreateAssayRequest.class),
                    examples = @ExampleObject(value = "{\"name\":\"sequencing-run-01\",\"libraryLayout\":\"PAIRED\",\"insertSize\":350}")))
            CreateAssayRequest request) {
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
                request.insertSize(),
                request.experimentAccession(),
                request.runAccession());

        return AssayResponse.fromEntity(assay);
    }

    @PATCH
    @Authenticated
    @Path("/{assayId}")
    @Operation(summary = "Update an assay", description = "Requires EDITOR membership. Supplied non-null fields are updated; returns no response body.")
    @APIResponse(responseCode = "204", description = "Assay updated")
    @APIResponse(responseCode = "403", description = "Project EDITOR membership required")
    @APIResponse(responseCode = "404", description = "Project or project-scoped assay not found")
    public Response updateAssay(
            @PathParam("projectId") Long projectId, @PathParam("assayId") UUID assayId,
            @RequestBody(description = "Partial assay update; only non-null values are applied",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = PatchAssayRequest.class),
                            examples = @ExampleObject(value = "{\"instrumentModel\":\"NextSeq 2000\"}")))
            PatchAssayRequest request) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        assayService.updateAssay(
                projectId,
                assayId,
                request.name(),
                request.studyAccession(),
                request.instrumentModel(),
                request.libraryName(),
                request.librarySource(),
                request.librarySelection(),
                request.libraryStrategy(),
                request.libraryLayout(),
                request.insertSize(),
                request.experimentAccession(),
                request.runAccession());

        return Response.noContent().build();
    }

    @POST
    @Authenticated
    @Path("/{assayId}/experiments")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Import experiments into an assay", description = "Requires EDITOR membership. Upload a CSV, TSV, XLS, or XLSX experiment sheet in multipart field `file`. Rows are processed independently, so successful rows may be committed even when the response is 400 with row-level errors. Row failures return a list of row errors; upload-level 400 responses use the framework's bad-request body.")
    @APIResponse(responseCode = "200", description = "All rows imported; no response body")
    @APIResponse(responseCode = "400", description = "Upload or spreadsheet invalid, or one or more rows were rejected",
            content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = CSVExperimentRowError.class)))
    @APIResponse(responseCode = "403", description = "Project EDITOR membership required")
    @APIResponse(responseCode = "404", description = "Project or assay not found")
    public Response importExperiments(
            @PathParam("projectId") Long projectId,
            @PathParam("assayId") UUID assayId,
            @Parameter(description = "CSV, TSV, XLS, or XLSX experiment sheet", required = true) @RestForm("file") FileUpload file) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();
        if (file == null) throw new BadRequestException("No file uploaded");

        TableUploadSupport.validate(file);

        List<CSVExperimentRowError> errors = csvExperimentImportService.importIntoAssay(
                projectId, assayId, file.filePath().toFile());
        return errors.isEmpty() ? Response.ok().build() : Response.status(400).entity(errors).build();
    }

    @DELETE
    @Authenticated
    @Path("/{assayId}")
    @Operation(summary = "Delete an assay", description = "Requires EDITOR membership in the assay's owning project. Returns no response body.")
    @APIResponse(responseCode = "204", description = "Assay deleted")
    @APIResponse(responseCode = "403", description = "Project EDITOR membership required, or assay belongs to a parent project")
    @APIResponse(responseCode = "404", description = "Project or project-scoped assay not found")
    public Response deleteAssay(@PathParam("projectId") Long projectId, @PathParam("assayId") UUID assayId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR))
            throw new WebApplicationException(Response.Status.FORBIDDEN);

        assayService.deleteAssay(projectId, assayId);
        return Response.noContent().build();
    }

    @PUT
    @Authenticated
    @Path("/{assayId}/samples")
    @Operation(summary = "Add samples to an assay", description = "Requires EDITOR membership. Sample names are resolved in the project; successful associations are applied even if other names are missing. Returns an array of error messages, empty when all names were processed successfully.")
    @APIResponse(responseCode = "403", description = "Project EDITOR membership required")
    @APIResponse(responseCode = "404", description = "Project or assay not found")
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
    @Operation(summary = "Remove samples from an assay", description = "Requires EDITOR membership. Sample names are resolved in the project; removals are applied for existing names and missing names are returned as error messages.")
    @APIResponse(responseCode = "403", description = "Project EDITOR membership required")
    @APIResponse(responseCode = "404", description = "Project or assay not found")
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
    @Operation(summary = "List samples in an assay", description = "Requires VIEWER membership. Includes active custom metadata for returned samples.")
    public List<SampleResponse> getSamplesInAssay(
            @PathParam("projectId") Long projectId, @PathParam("assayId") UUID assayId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER))
            throw new WebApplicationException(Response.Status.FORBIDDEN);
        List<Sample> samples = assayService.getAllSamplesInAssay(projectId, assayId);
        Map<UUID, Map<String, Object>> metadata = metadataService.getActiveMetadata(samples);

        return samples.stream()
                .map(sample -> SampleResponse.fromEntity(sample, metadata.get(sample.id)))
                .toList();
    }

    @GET
    @Authenticated
    @Path("/{assayId}/files")
    @Operation(summary = "List files in an assay", description = "Requires VIEWER membership in the project.")
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
    @Operation(summary = "List files for a sample in an assay", description = "Requires VIEWER membership in the project.")
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
