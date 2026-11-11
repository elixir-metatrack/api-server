package no.metatrack.server.admin;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import no.metatrack.server.auth.UserService;

import java.io.IOException;
import java.util.UUID;

@Provider
@Priority(Priorities.USER)
public class AdminAuditFilter implements ContainerRequestFilter, ContainerResponseFilter {
    private static final String AUDIT_ACTOR_PROPERTY = AdminAuditFilter.class.getName() + ".actor";

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    UserService userService;

    @Inject
    AdminAuditService auditService;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!securityIdentity.hasRole("system-admin") || isReadOnly(requestContext.getMethod())) {
            return;
        }
        try {
            requestContext.setProperty(AUDIT_ACTOR_PROPERTY,
                    UUID.fromString(userService.requireCurrentUser().id()));
        } catch (RuntimeException ignored) {
            // An unidentifiable caller must not be recorded as a privileged actor.
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext)
            throws IOException {
        Object actor = requestContext.getProperty(AUDIT_ACTOR_PROPERTY);
        if (actor instanceof UUID actorId) {
            auditService.record(actorId, requestContext.getMethod(), requestContext.getUriInfo().getPath(),
                    responseContext.getStatus());
        }
    }

    private boolean isReadOnly(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }
}