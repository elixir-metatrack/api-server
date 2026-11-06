package no.metatrack.server.upload;

import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

@ApplicationScoped
@Path("/api/internal/sftpgo")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class SftpGoEventResource {
    private static final String SECRET_HEADER = "Authorization";
    private static final String HOOK_USERNAME = "metatrack-sftpgo-hook";

    private final SftpGoConfig config;
    private final SftpGoUploadPolicy uploadPolicy;

    public SftpGoEventResource(SftpGoConfig config, SftpGoUploadPolicy uploadPolicy) {
        this.config = config;
        this.uploadPolicy = uploadPolicy;
    }

    @POST
    @Path("/events")
    @PermitAll
    public Response receive(@HeaderParam(SECRET_HEADER) String suppliedSecret, SftpGoHookEvent event) {
        String hookSecret = config.hookSecret().orElse("");
        if (!config.enabled() || hookSecret.isBlank()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "sftpgo_hook_unavailable")).build();
        }
        if (!matchesSecret(suppliedSecret, hookSecret)) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", "invalid_sftpgo_hook_authentication")).build();
        }
        if (event == null || event.event() == null || event.username() == null || event.username().isBlank()
                || event.path() == null || !(event.event().equals("pre-upload") || event.event().equals("upload"))) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "invalid_sftpgo_event")).build();
        }

        String decodedPath;
        try {
            decodedPath = relativePath(event.path());
        } catch (IllegalArgumentException exception) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "invalid_sftpgo_path")).build();
        }
        if (decodedPath == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "invalid_sftpgo_path")).build();
        }

        boolean permitted;
        try {
            permitted = uploadPolicy.permits(event.withDecodedPath(decodedPath));
        } catch (SftpGoIntegrationException exception) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(Map.of("error", "sftpgo_upload_admission_unavailable")).build();
        }
        if (!permitted) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "sftpgo_upload_not_authorized")).build();
        }
        return Response.ok(Map.of("status", "accepted")).build();
    }

    private boolean matchesSecret(String suppliedSecret, String expectedSecret) {
        if (suppliedSecret == null || !suppliedSecret.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String credentials;
        try {
            credentials = new String(Base64.getDecoder().decode(suppliedSecret.substring(6)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        int separator = credentials.indexOf(':');
        if (separator < 0 || !HOOK_USERNAME.equals(credentials.substring(0, separator))) {
            return false;
        }
        return MessageDigest.isEqual(credentials.substring(separator + 1).getBytes(StandardCharsets.UTF_8),
                expectedSecret.getBytes(StandardCharsets.UTF_8));
    }

    private String relativePath(String escapedPath) {
        String decoded = URLDecoder.decode(escapedPath, StandardCharsets.UTF_8);
        if (decoded.indexOf('\0') >= 0 || decoded.indexOf('\\') >= 0 || decoded.isBlank()) {
            return null;
        }
        String relative = decoded.startsWith("/") ? decoded.substring(1) : decoded;
        String[] segments = relative.split("/", -1);
        for (String segment : segments) {
            if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                return null;
            }
        }
        return relative;
    }
}