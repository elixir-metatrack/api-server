package no.metatrack.server.spreadsheet;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

@Path("/api/templates/excel")
public class SpreadsheetTemplateController {
    public static final String XLSX_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    @Inject SpreadsheetTemplateService templates;

    @GET
    @Path("/{type}")
    @Produces(XLSX_TYPE)
    public Response download(@PathParam("type") String type) {
        byte[] content = templates.create(type);
        return Response.ok(content)
                .header("Content-Disposition", "attachment; filename=\"" + type + "-v1.xlsx\"")
                .build();
    }
}
