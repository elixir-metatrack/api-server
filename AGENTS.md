# Repository guidance

## Project

This repository is a Java 21 REST API built with Quarkus 3, Maven, Hibernate ORM with Panache, PostgreSQL, and Flyway. The application configuration is in `src/main/resources/application.yml`; database migrations are in `src/main/resources/db/migration/`. Use the Maven wrapper (`./mvnw`) so the project’s pinned Maven setup is respected.

## Build and run

- Start Quarkus live reload: `./mvnw quarkus:dev` (HTTP port 1234).
- Run unit tests: `./mvnw test`.
- Run a single test class: `./mvnw test -Dtest=ClassName`.
- Package the application: `./mvnw package`.
- Run integration tests with `./mvnw verify` when they are relevant.
- Native builds require GraalVM or the configured container build; see the README before starting one.

Invitation and notification database tests are opt-in. Only run them with the documented `INVITATION_TEST_*` variables pointed at a disposable test database; never use production data.

## Code organization and patterns

Application code is under `src/main/java/no/metatrack/server/`, organized by domain (`auth`, `project`, `sample`, `assay`, `file`, `invitation`, `notification`, `taxon`, `stats`, and `health`). Follow nearby code in the affected domain before introducing a new pattern.

- Keep JAX-RS controllers focused on HTTP handling, authentication, request validation, and mapping. Put business logic in `@ApplicationScoped` services.
- Use `@Transactional` for database mutations that must commit or roll back together.
- Entities use Hibernate ORM with Panache; follow the existing Active Record and query conventions in the relevant domain.
- Preserve project-level authorization through the existing `ProjectRoleCheck` and role model. Do not rely on authentication alone for project access.
- Add schema changes as ordered Flyway migrations. Keep entity mappings, API DTOs, and migration behavior consistent.
- Keep API response and error behavior aligned with the published OpenAPI contract and established exception mappers.

## Integrations and configuration

The service integrates with OIDC/Keycloak, PostgreSQL, S3-compatible object storage, SMTP, and an external taxonomy API. Read the relevant configuration and service code before changing integration behavior. Use configuration/environment variables for credentials and endpoints; do not commit secrets or real `.env` values.

For CSV/TSV imports and bulk operations, preserve the established row-level error reporting and partial-processing behavior. Check the relevant import service and tests before changing validation or transaction boundaries.

## Documentation

Update the README or API documentation when a change alters setup, configuration, externally visible API behavior, or operational requirements.
