package no.metatrack.server.upload;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ServiceUnavailableException;
import no.metatrack.server.auth.UserService;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.util.UUID;

@Path("/api/projects/{projectId}/upload-batches")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Upload batches", description = "Manifest-based SFTP batch uploads")
public class UploadBatchController {
    @Inject
    UserService userService;

    @Inject
    UploadBatchRegistrationService registrationService;

    @Inject
    UploadBatchProvisioningService provisioningService;

    @Inject
    UploadBatchQueryService queryService;

    @Inject
    UploadBatchLifecycleService lifecycleService;

    @Inject
    SftpGoConfig sftpGoConfig;

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Register an SFTP upload batch",
            description = "Validates a CSV manifest against existing samples and assays, reserves declared staging bytes, and provisions a public-key-only SFTP account.")
    @APIResponse(responseCode = "201", description = "Batch registered and SFTP account provisioned",
            content = @Content(schema = @Schema(implementation = UploadBatchReport.class)))
    @APIResponse(responseCode = "202", description = "Batch registered; SFTP account provisioning is retrying",
            content = @Content(schema = @Schema(implementation = UploadBatchReport.class)))
    @APIResponse(responseCode = "400", description = "Manifest or public key validation failed")
    @APIResponse(responseCode = "403", description = "EDITOR-or-higher membership required in the project and parent project")
    @APIResponse(responseCode = "507", description = "Staging budget exhausted")
    public Response create(
            @PathParam("projectId") Long projectId,
            @RestForm("manifest") FileUpload manifest,
            @RestForm("sshPublicKey") String sshPublicKey) {
        if (!sftpGoConfig.enabled()) {
            throw new ServiceUnavailableException("SFTPGo batch uploads are not enabled");
        }
        UUID uploaderId = UUID.fromString(userService.requireCurrentUser().id());
        UploadBatchRegistrationResult result = registrationService.register(
                projectId, uploaderId, manifest, sshPublicKey);
        if (!result.accepted()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(result.issues()).build();
        }
        boolean provisioned = provisioningService.provision(result.batchId());
        UploadBatchReport report = queryService.getReport(projectId, result.batchId(), uploaderId);
        return Response.status(provisioned ? Response.Status.CREATED : Response.Status.ACCEPTED)
                .entity(report)
                .build();
    }

    @GET
    @Path("/{batchId}")
    @Operation(summary = "Get upload batch status",
            description = "Returns per-entry progress and errors to the batch creator or a project ADMIN/OWNER.")
    @APIResponse(responseCode = "200", description = "Upload batch report", content = @Content(schema = @Schema(implementation = UploadBatchReport.class)))
    @APIResponse(responseCode = "403", description = "Upload batch access denied")
    @APIResponse(responseCode = "404", description = "Batch not found in this project")
    public UploadBatchReport get(
            @PathParam("projectId") Long projectId,
            @PathParam("batchId") UUID batchId) {
        UUID requesterId = UUID.fromString(userService.requireCurrentUser().id());
        return queryService.getReport(projectId, batchId, requesterId);
    }

    @POST
    @Path("/{batchId}/finalize")
    @Operation(summary = "Freeze and finalize an upload batch",
            description = "Closes new SFTP writes and starts draining active transfers before asynchronous processing.")
    @APIResponse(responseCode = "202", description = "Batch frozen; transfer drain is in progress",
            content = @Content(schema = @Schema(implementation = UploadBatchReport.class)))
    @APIResponse(responseCode = "409", description = "Batch expired before finalization",
            content = @Content(schema = @Schema(implementation = UploadBatchReport.class)))
    @APIResponse(responseCode = "403", description = "Batch access or uploader permission denied")
    public Response finalizeBatch(
            @PathParam("projectId") Long projectId,
            @PathParam("batchId") UUID batchId) {
        UUID requesterId = UUID.fromString(userService.requireCurrentUser().id());
        UploadBatchStatus status = lifecycleService.finalizeBatch(projectId, batchId, requesterId);
        UploadBatchReport report = queryService.getReport(projectId, batchId, requesterId);
        if (status == UploadBatchStatus.EXPIRED) {
            return Response.status(Response.Status.CONFLICT).entity(report).build();
        }
        return Response.accepted(report).build();
    }

    @DELETE
    @Path("/{batchId}")
    @Operation(summary = "Cancel an upload batch",
            description = "Prevents further writes and publication and retains staging data for the configured recovery window.")
    @APIResponse(responseCode = "202", description = "Batch cancellation accepted",
            content = @Content(schema = @Schema(implementation = UploadBatchReport.class)))
    @APIResponse(responseCode = "403", description = "Batch access or uploader permission denied")
    public Response cancelBatch(
            @PathParam("projectId") Long projectId,
            @PathParam("batchId") UUID batchId) {
        UUID requesterId = UUID.fromString(userService.requireCurrentUser().id());
        lifecycleService.cancelBatch(projectId, batchId, requesterId);
        return Response.accepted(queryService.getReport(projectId, batchId, requesterId)).build();
    }
}