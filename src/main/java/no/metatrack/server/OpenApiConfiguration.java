package no.metatrack.server;

import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

@SecurityScheme(securitySchemeName = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@OpenAPIDefinition(info = @Info(
        title = "MetaTrack REST API",
        version = "0.7.0",
        description = "API for projects, samples, assays, files, and statistics. Authentication and project-role requirements are documented per operation; public catalog and utility endpoints do not require a bearer token."))
public class OpenApiConfiguration {}