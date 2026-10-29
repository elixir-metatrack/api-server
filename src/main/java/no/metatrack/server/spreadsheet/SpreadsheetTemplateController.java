package no.metatrack.server.spreadsheet;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

@Path("/api/templates/excel")
@Tag(name = "Spreadsheet templates", description = "Downloadable XLSX templates for sample and experiment imports")
public class SpreadsheetTemplateController {
    public static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    @Inject SpreadsheetTemplateService templates;

    @GET
    @Path("/{type}")
    @Produces(XLSX_TYPE)
    @Operation(summary = "Download an Excel import template", description = "Returns an XLSX template. Types are `sample`, `sample_extended`, `sample_virus`, `experiment_PE`, and `experiment_SE`; sample templates include a vocabularies worksheet.")
    @APIResponse(responseCode = "200", description = "XLSX template attachment", content = @Content(mediaType = XLSX_TYPE, schema = @Schema(type = SchemaType.STRING, format = "binary")))
    @APIResponse(responseCode = "404", description = "Unknown template type")
    public Response download(@PathParam("type") String type) {
        byte[] content = templates.create(type);
        return Response.ok(content)
                .header("Content-Disposition", "attachment; filename=\"" + type + "-v1.xlsx\"")
                .build();
    }
}
