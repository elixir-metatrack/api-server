package no.metatrack.server.sample;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.assay.Assay;
import no.metatrack.server.assay.AssayResponse;
import no.metatrack.server.assay.AssayService;
import no.metatrack.server.file.File;
import no.metatrack.server.file.FileResponse;
import no.metatrack.server.file.FileService;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import no.metatrack.server.sample.metadata.SampleMetadataService;
import no.metatrack.server.sample.vocabulary.SampleValidationViolation;
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

@Path("/api/projects/{projectId}/samples")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Samples", description = "Project-scoped sample records, metadata, imports, and sub-project links")
public class SampleController {

    @Inject
    SampleService sampleService;

    @Inject
    FileService fileService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @Inject
    CSVSampleSheetImportService csvSampleSheetImportService;


    @Inject
    SampleMetadataService metadataService;

    @Inject
    AssayService assayService;

    @GET
    @Authenticated
    @Operation(summary = "List project samples", description = "Requires VIEWER membership. Includes active custom metadata for each sample.")
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
    @Operation(summary = "Get a sample", description = "Requires VIEWER membership in the project. Includes active custom metadata.")
    @APIResponse(responseCode = "404", description = "Project or project-scoped sample not found")
    public SampleResponse getSample(@PathParam("projectId") Long projectId, @PathParam("sampleId") UUID sampleId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();

        Sample sample = sampleService.getSampleById(sampleId, projectId);
        return SampleResponse.fromEntity(sample, metadataService.getActiveMetadata(sample));
    }

    @GET
    @Path("/name/{sampleName}")
    @Authenticated
    @Operation(summary = "Get a sample by name", description = "Sample names are resolved within the specified project. Requires VIEWER membership.")
    @APIResponse(responseCode = "404", description = "Project or project-scoped sample not found")
    public SampleResponse getSampleByName(
            @PathParam("projectId") Long projectId, @PathParam("sampleName") String sampleName) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();

        Sample sample = sampleService.getSampleByName(sampleName, projectId);

        return SampleResponse.fromEntity(sample, metadataService.getActiveMetadata(sample));
    }

    @POST
    @Authenticated
    @Operation(summary = "Create a sample", description = "Requires EDITOR membership. Returns the created sample with active custom metadata; project vocabulary rules are validated.")
    @APIResponse(responseCode = "403", description = "Project EDITOR membership required")
    @APIResponse(responseCode = "404", description = "Project not found")
    public SampleResponse createSample(
            @PathParam("projectId") Long projectId,
            @RequestBody(description = "Sample fields to create", content = @Content(mediaType = MediaType.APPLICATION_JSON,
                    schema = @Schema(implementation = CreateSampleRequest.class),
                    examples = @ExampleObject(value = "{\"name\":\"sample-001\",\"collectionDate\":\"2026-08-15\",\"location\":\"Oslo\"}")))
            @Valid CreateSampleRequest request) {
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
                request.projectAccession(),
                request.studyAccession(),
                request.sampleAccession(),
                request.secondaryAccession(),
                request.customMetadata());

        return SampleResponse.fromEntity(sample, metadataService.getActiveMetadata(sample));
    }

    @PATCH
    @Authenticated
    @Path("/{sampleId}")
    @Operation(summary = "Update a sample", description = "Requires EDITOR membership. Non-null request fields are updated; returns no response body.")
    @APIResponse(responseCode = "204", description = "Sample updated")
    @APIResponse(responseCode = "403", description = "Project EDITOR membership required")
    @APIResponse(responseCode = "404", description = "Project or project-scoped sample not found")
    public Response updateSample(
            @PathParam("projectId") Long projectId,
            @PathParam("sampleId") UUID sampleId,
            @RequestBody(description = "Partial sample update; only non-null values are applied",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = PatchSampleRequest.class),
                            examples = @ExampleObject(value = "{\"location\":\"Oslo\"}")))
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
                request.projectAccession(),
                request.studyAccession(),
                request.sampleAccession(),
                request.secondaryAccession(),
                request.customMetadata());

        return Response.noContent().build();
    }

    @DELETE
    @Authenticated
    @Path("/{sampleId}")
    @Operation(summary = "Delete a sample", description = "Requires EDITOR membership in the owning project. Linked samples must be removed from sub-projects before deleting the parent sample; returns no response body.")
    @APIResponse(responseCode = "204", description = "Sample deleted")
    @APIResponse(responseCode = "403", description = "Project EDITOR membership required, or sample is linked from a sub-project")
    @APIResponse(responseCode = "404", description = "Project or project-scoped sample not found")
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
    @Operation(summary = "Import samples from a spreadsheet", description = "Requires EDITOR membership. Upload a CSV, TSV, XLS, or XLSX sample sheet in multipart field `file`. Rows are processed independently: valid rows may be saved even when the response is 400 with row-level validation violations. Row failures return a list of violations; upload-level 400 responses use the framework's bad-request body.")
    @APIResponse(responseCode = "200", description = "All rows imported; no response body")
    @APIResponse(responseCode = "400", description = "Upload or spreadsheet invalid, or one or more rows were rejected",
            content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = SampleValidationViolation.class)))
    @APIResponse(responseCode = "403", description = "Project EDITOR membership required")
    @APIResponse(responseCode = "404", description = "Project not found")
    public Response importCSV(@PathParam("projectId") Long projectId,
            @Parameter(description = "CSV, TSV, XLS, or XLSX sample sheet", required = true) @RestForm("file") FileUpload file) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();

        if (file == null) throw new BadRequestException("No file uploaded");

        TableUploadSupport.validate(file);

        List<SampleValidationViolation> errors = csvSampleSheetImportService.importNewSamples(
                projectId, file.filePath().toFile());

        if (errors == null || errors.isEmpty()) {
            return Response.ok().build();
        }

        return Response.status(400).entity(errors).build();
    }

    @PATCH
    @Authenticated
    @Operation(summary = "Bulk update samples", description = "Requires EDITOR membership. Each sample entry is validated and processed independently; valid updates are retained when other entries fail, with violations returned as a 400 response.")
    @APIResponse(responseCode = "200", description = "All sample entries updated; no response body")
    @APIResponse(responseCode = "400", description = "One or more sample entries failed validation",
            content = @Content(schema = @Schema(type = SchemaType.ARRAY, implementation = SampleValidationViolation.class)))
    @APIResponse(responseCode = "403", description = "Project EDITOR membership required")
    @APIResponse(responseCode = "404", description = "Project not found")
    public Response batchUpdateSamples(
            @PathParam("projectId") Long projectId,
            @RequestBody(description = "Sample updates keyed by existing sample name",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON,
                            schema = @Schema(implementation = BulkPatchSampleRequest.class),
                            examples = @ExampleObject(value = "{\"sampleData\":[{\"name\":\"sample-001\",\"location\":\"Oslo\"}]}")))
            @Valid BulkPatchSampleRequest request) {
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
    @Operation(summary = "List files attached to a sample", description = "Requires VIEWER membership in the project.")
    public List<FileResponse> getAllFilesInSample(
            @PathParam("projectId") Long projectId, @PathParam("sampleId") UUID sampleId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();

        List<File> files = fileService.getAllFilesInSample(projectId, sampleId);
        return files.stream().map(FileResponse::fromEntity).toList();
    }


    @GET
    @Authenticated
    @Path("/{sampleId}/assays")
    @Operation(summary = "List assays for a sample", description = "Requires VIEWER membership in the project.")
    public List<AssayResponse> getAllAssaysInSample(
            @PathParam("projectId") Long projectId, @PathParam("sampleId") UUID sampleId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();

        List<Assay> assays = assayService.getAllAssaysInSample(projectId, sampleId);
        return assays.stream().map(AssayResponse::fromEntity).toList();
    }

    @PUT
    @Authenticated
    @Path("/link")
    @Operation(summary = "Link samples to a sub-project", description = "Requires ADMIN membership in the parent project. Every sample ID must belong to that parent; this endpoint is scoped by the sub-project ID.")
    @APIResponse(responseCode = "204", description = "Samples linked; no response body")
    @APIResponse(responseCode = "400", description = "Target is not a sub-project or a sample does not belong to the parent")
    @APIResponse(responseCode = "403", description = "Parent project ADMIN membership required")
    @APIResponse(responseCode = "404", description = "Project or sample not found")
    public Response linkSamples(@PathParam("projectId") Long projectId, @Valid LinkSamplesRequest request) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");

        sampleService.linkSamples(projectId, request.sampleIds());
        return Response.noContent().build();
    }

    @DELETE
    @Authenticated
    @Path("/link")
    @Operation(summary = "Unlink samples from a sub-project", description = "Requires ADMIN membership in the parent project. Sample IDs not currently linked are ignored; this endpoint is scoped by the sub-project ID.")
    @APIResponse(responseCode = "204", description = "Samples unlinked; no response body")
    @APIResponse(responseCode = "400", description = "Target is not a sub-project")
    @APIResponse(responseCode = "403", description = "Parent project ADMIN membership required")
    @APIResponse(responseCode = "404", description = "Project not found")
    public Response unlinkSamples(@PathParam("projectId") Long projectId, @Valid LinkSamplesRequest request) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");

        sampleService.unlinkSamples(projectId, request.sampleIds());
        return Response.noContent().build();
    }
}
