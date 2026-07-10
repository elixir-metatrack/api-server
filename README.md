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
- **Object Storage:** S3-compatible storage such as Garage, AWS S3, or MinIO (presigned URLs and upload reconciliation)
- **Containerization:** Jib (Docker)
- **Build Tool:** Maven (via `./mvnw` wrapper)

## Requirements

- **Java:** JDK 21+
- **Database:** PostgreSQL 16+ (recommended)
- **Storage:** Access to an S3-compatible object store and an existing bucket
- **Auth:** Access to Metatrack Keycloak realm

## Environment Variables

The following environment variables are required for the application to function correctly:

| Variable           | Description                                                     |
|--------------------|-----------------------------------------------------------------|
| `DB_PASSWORD`      | Password for the PostgreSQL database                            |
| `S3_ENDPOINT`      | Full S3 API endpoint URL, including scheme and port             |
| `S3_REGION`        | S3 signing region                                                |
| `S3_ACCESS_KEY`    | S3 access key                                                    |
| `S3_SECRET_KEY`    | S3 secret key                                                    |
| `S3_BUCKET_NAME`   | Object bucket; defaults to `metatrack`                           |
| `S3_PATH_STYLE_ACCESS` | Use path-style addressing; defaults to `true`               |
| `FILE_RECONCILIATION_INTERVAL` | Pending-upload polling interval; defaults to `10s`  |

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
├── file/       # File metadata, generic S3 URLs, and upload reconciliation
├── stats/      # Platform-wide statistics
└── health/     # Service health status endpoints

src/main/resources/
├── application.yml    # Configuration (ports, DB, OIDC, S3)
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
