# Metatrack Server

Metatrack Server is a Quarkus-based REST API for the Metatrack platform—a comprehensive sample and assay tracking system
for biological and genomic data. It provides robust management of projects, samples, assays, and associated files.

## Tech Stack

- **Language:** Java 21
- **Framework:** [Quarkus](https://quarkus.io/) (with virtual threads enabled)
- **Database:** PostgreSQL
- **ORM:** Hibernate ORM with Panache (Active Record pattern)
- **Migrations:** Flyway
- **Authentication:** OIDC / Keycloak (Service type)
- **Object Storage:** MinIO (for file uploads/downloads via presigned URLs)
- **Containerization:** Jib (Docker)
- **Build Tool:** Maven (via `./mvnw` wrapper)

## Requirements

- **Java:** JDK 21+
- **Database:** PostgreSQL 16+ (recommended)
- **Storage:** MinIO server access
- **Auth:** Access to Metatrack Keycloak realm

## Environment Variables

The following environment variables are required for the application to function correctly:

| Variable           | Description                                                     |
|--------------------|-----------------------------------------------------------------|
| `DB_PASSWORD`      | Password for the PostgreSQL database                            |
| `MINIO_ACCESS_KEY` | Access key for MinIO storage                                    |
| `MINIO_SECRET_KEY` | Secret key for MinIO storage (also used for webhook validation) |

## Setup and Running

### Running in Development Mode

You can run the application in dev mode with live coding enabled:

```shell
./mvnw quarkus:dev
```

> **Note:** The server defaults to port `1234` (configured in `application.yml`). The Quarkus Dev UI is available at
`http://localhost:1234/q/dev/`.

### Packaging and Running

To package the application into a runnable JAR:

```shell
./mvnw package
```

The output `quarkus-run.jar` and its dependencies will be in `target/quarkus-app/`. Run it with:

```shell
java -jar target/quarkus-app/quarkus-run.jar
```

To build an **über-jar**:

```shell
./mvnw package -Dquarkus.package.jar.type=uber-jar
java -jar target/*-runner.jar
```

### Native Executable

Build a native executable (requires GraalVM or Docker):

```shell
./mvnw package -Dnative
# Or using a container for the build
./mvnw package -Dnative -Dquarkus.native.container-build=true
```

Run the native executable: `./target/server-1.0-SNAPSHOT-runner`

## Scripts and Commands

- `./mvnw compile` - Compile the project.
- `./mvnw test` - Run unit and integration tests.
- `./mvnw quarkus:dev` - Start development server.
- `./mvnw package` - Build the application.
- `./mvnw clean` - Remove build artifacts.

## Project Structure

```text
src/main/java/no/metatrack/server/
├── auth/       # OIDC authentication and user management
├── project/    # Project lifecycle, memberships, and roles
├── sample/     # Sample management and CSV/TSV import
├── assay/      # Assay grouping and sample associations
├── file/       # File metadata and MinIO presigned URL service
├── stats/      # Platform-wide statistics
└── health/     # Service health status endpoints

src/main/resources/
├── application.yml    # Configuration (ports, DB, OIDC, MinIO)
└── db/migration/      # Flyway SQL migration scripts
```

## API Documentation

When the application is running, the OpenAPI UI (scalar) is available at:
`http://localhost:1234/scalar`.

## Tests

Run the test suite:

```shell
./mvnw test
```

Integration tests can be run with:

```shell
./mvnw verify
```

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

---
*This project was generated using Quarkus.*
