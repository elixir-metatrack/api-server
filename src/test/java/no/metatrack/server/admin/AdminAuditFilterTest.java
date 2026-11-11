package no.metatrack.server.admin;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.UriInfo;
import no.metatrack.server.auth.CurrentUser;
import no.metatrack.server.auth.UserService;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuditFilterTest {
    @Test
    void recordsMutatingAdminRequestsWithoutReadingRequestBodies() throws Exception {
        UUID actorId = UUID.randomUUID();
        SecurityIdentity securityIdentity = mock(SecurityIdentity.class);
        UserService userService = mock(UserService.class);
        AdminAuditService auditService = mock(AdminAuditService.class);
        AdminAuditFilter filter = new AdminAuditFilter();
        filter.securityIdentity = securityIdentity;
        filter.userService = userService;
        filter.auditService = auditService;
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        ContainerResponseContext response = mock(ContainerResponseContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(securityIdentity.hasRole("system-admin")).thenReturn(true);
        when(userService.requireCurrentUser()).thenReturn(
                new CurrentUser(actorId.toString(), "admin", Set.of("system-admin"), null, null, null));
        when(request.getMethod()).thenReturn("POST");
        when(request.getProperty(AdminAuditFilter.class.getName() + ".actor")).thenReturn(actorId);
        when(request.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("api/admin/projects/4/ownership");
        when(response.getStatus()).thenReturn(204);

        filter.filter(request);
        filter.filter(request, response);

        verify(auditService).record(actorId, "POST", "api/admin/projects/4/ownership", 204);
    }

    @Test
    void doesNotRecordReadOnlyRequests() throws Exception {
        SecurityIdentity securityIdentity = mock(SecurityIdentity.class);
        UserService userService = mock(UserService.class);
        AdminAuditService auditService = mock(AdminAuditService.class);
        AdminAuditFilter filter = new AdminAuditFilter();
        filter.securityIdentity = securityIdentity;
        filter.userService = userService;
        filter.auditService = auditService;
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(securityIdentity.hasRole("system-admin")).thenReturn(true);
        when(request.getMethod()).thenReturn("GET");

        filter.filter(request);

        verify(userService, never()).requireCurrentUser();
        verify(auditService, never()).record(any(), anyString(), anyString(), anyInt());
    }
}