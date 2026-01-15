package no.metatrack.server.assay;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.Project;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;

import java.util.List;
import java.util.UUID;

@Path("/api/projects/{projectId}/assays")
public class AssayController {
    @Inject
    AssayService assayService;

    @Inject
    ProjectRoleCheck projectRoleCheck;

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
}
