package no.metatrack.server.sample;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.List;
import java.util.UUID;

@Path("/api/projects/{projectId}/samples")
public class SampleController {
    @Inject
    SampleServices sampleServices;

    @Inject
    ProjectRoleCheck projectRoleCheck;

    @Inject
    CSVSampleSheetImportService csvSampleSheetImportService;

    @GET
    @Authenticated
    public List<Sample> getAllSamples(@PathParam("projectId") Long projectId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();
        return sampleServices.getAllSamples(projectId);
    }

    @GET
    @Path("/{sampleId}")
    @Authenticated
    public Sample getSample(@PathParam("projectId") Long projectId, @PathParam("sampleId") UUID sampleId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();

        return sampleServices.getSampleById(sampleId);
    }

    @GET
    @Path("/name/{sampleName}")
    @Authenticated
    public Sample getSampleByName(@PathParam("projectId") Long projectId, @PathParam("sampleName") String sampleName) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.VIEWER)) throw new ForbiddenException();

        return sampleServices.getSampleByName(sampleName, projectId);
    }

    @POST
    @Authenticated
    public Sample createSample(@PathParam("projectId") Long projectId, @Valid CreateSampleRequest request) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();

        return sampleServices.createSample(
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
                request.collectionDate());
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

        sampleServices.updateSample(
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
                request.collectionDate());

        return Response.noContent().build();
    }

    @DELETE
    @Authenticated
    @Path("/{sampleId}")
    public Response deleteSample(@PathParam("projectId") Long projectId, @PathParam("sampleId") UUID sampleId) {
        if (!Project.projectExists(projectId)) throw new NotFoundException("Project not found");
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();

        sampleServices.deleteSample(sampleId);
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

        List<CSVUploadRowError> error = csvSampleSheetImportService.importNewSamples(
                projectId, file.filePath().toFile());

        if (!error.isEmpty()) return Response.ok(error).build();

        return Response.noContent().build();
    }
}
