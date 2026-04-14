package no.metatrack.server.file;

import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

@Path("/webhook")
public class MinioWebhookController {

    private static final Logger LOG = Logger.getLogger(MinioWebhookController.class);

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
            LOG.errorf(e, "Failed to process MinIO webhook event: %s", e.getMessage());
        }
        return Response.accepted().build();
    }
}
