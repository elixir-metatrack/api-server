package no.metatrack.server.isa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;

@Path("/api/projects/{projectId}/imports/isa-json")
@Authenticated
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class IsaJsonImportController {
    @Inject ProjectRoleCheck projectRoleCheck;
    @Inject IsaJsonImportService importService;
    @Inject ObjectMapper mapper;

    @POST
    @Operation(summary = "Import an ISA-JSON study (experimental)",
            description = "Without commit=true this only returns a preview. A commit creates samples, custom fields, "
                    + "experiments and pending file records in one transaction, or nothing if any error is reported.")
    public Response importIsaJson(
            @PathParam("projectId") Long projectId,
            @Parameter(description = "Identifier of the study to import if the document contains several")
            @QueryParam("study") String study,
            @QueryParam("commit") @DefaultValue("false") boolean commit,
            String body) {
        if (!projectRoleCheck.isAtLeast(projectId, ProjectRole.EDITOR)) throw new ForbiddenException();
        JsonNode document;
        try {
            // Keep decimal precision of numeric values.
            document = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(body);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("The file is not valid JSON");
        }
        if (document == null || !document.isObject()) throw new BadRequestException("Expected an ISA-JSON object");
        IsaJsonImportService.Report report = importService.importDocument(projectId, document, study, commit,
                projectRoleCheck.isAtLeast(projectId, ProjectRole.ADMIN));
        int status = commit && !report.committed() ? 422 : 200;
        return Response.status(status).entity(report).build();
    }
}
