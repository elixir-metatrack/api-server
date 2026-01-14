package no.metatrack.server.file;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

@Path("/webhook")
public class MinioWebhookController {
    @Inject
    FileIngestService fileIngestService;

    @POST
    @Path("/minio")
    public Response processWebhook(MinioEvent event) {

        if (event == null || event.Records() == null) {
            return Response.accepted().build();
        }
        try {
            event.Records().forEach(r -> fileIngestService.handleObjectCreated(r));
        } catch (Exception e) {
            return Response.accepted().build();
        }
        return Response.accepted().build();
    }
}
