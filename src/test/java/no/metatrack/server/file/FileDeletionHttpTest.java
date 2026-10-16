package no.metatrack.server.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import no.metatrack.server.project.ProjectRole;
import no.metatrack.server.project.ProjectRoleCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(value = SubProjectDatabase.class, restrictToAnnotatedClass = true)
@TestProfile(SubProjectTestProfile.class)
class FileDeletionHttpTest {
    @TestHTTPResource URI baseUri;
    @Inject ObjectMapper mapper;
    private ProjectRoleCheck roles;
    private FileIngestService service;
    private final UUID fileUuid = UUID.randomUUID();
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeEach
    void setUp() {
        roles = mock(ProjectRoleCheck.class);
        service = mock(FileIngestService.class);
        QuarkusMock.installMockForType(roles, ProjectRoleCheck.class);
        QuarkusMock.installMockForType(service, FileIngestService.class);
    }

    @Test
    void anonymousIsRejectedBeforeAuthorizationOrDeletion() throws Exception {
        assertEquals(401, delete("/api/projects/2/files/" + fileUuid).statusCode());
        verifyNoInteractions(roles, service);
    }

    @Test
    @TestSecurity(user = "editor")
    void authorizedDeletionReturnsNoContent() throws Exception {
        when(roles.isAtLeast(2L, ProjectRole.EDITOR)).thenReturn(true);
        var response = delete("/api/projects/2/files/" + fileUuid);
        assertEquals(204, response.statusCode());
        assertEquals("", response.body());
        verify(service).deleteFile(2L, fileUuid);
    }

    @Test
    @TestSecurity(user = "viewer")
    void insufficientMembershipIsForbiddenBeforeDeletion() throws Exception {
        assertEquals(403, delete("/api/projects/2/files/" + fileUuid).statusCode());
        verifyNoInteractions(service);
    }

    @Test
    @TestSecurity(user = "editor")
    void serviceErrorsKeepTheirHttpStatus() throws Exception {
        when(roles.isAtLeast(2L, ProjectRole.EDITOR)).thenReturn(true);
        for (int status : new int[]{404, 409, 502}) {
            doThrow(new WebApplicationException("File operation failed", status)).when(service).deleteFile(2L, fileUuid);
            assertEquals(status, delete("/api/projects/2/files/" + fileUuid).statusCode());
        }
    }

    @Test
    @TestSecurity(user = "editor")
    void oldRouteIsNotAvailable() throws Exception {
        assertEquals(404, delete("/api/projects/2/samples/" + UUID.randomUUID() + "/files/" + fileUuid).statusCode());
        verifyNoInteractions(service);
    }

    @Test
    void openApiPublishesOnlyTheNewDeleteContract() throws Exception {
        var request = HttpRequest.newBuilder(baseUri.resolve("/openapi?format=json")).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var paths = mapper.readTree(response.body()).path("paths");
        var operation = paths.path("/api/projects/{projectId}/files/{fileUuid}").path("delete");
        assertFalse(operation.isMissingNode());
        for (String code : new String[]{"204", "401", "403", "404", "409", "502"}) {
            assertTrue(operation.path("responses").has(code), code);
        }
        assertFalse(paths.path("/api/projects/{projectId}/samples/{sampleId}/files/{fileUuid}").has("delete"));
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(baseUri.resolve(path)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }
}