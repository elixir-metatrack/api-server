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

## Sample Controlled Vocabularies

Projects can optionally restrict values accepted by eligible sample text fields. A project vocabulary applies to one
canonical field key and is enforced by the API for direct sample creates and updates, bulk patches, and CSV/TSV
imports. Eligible fields without a configured vocabulary remain unrestricted.

### Discovering Eligible Fields

Authenticated project viewers can load all eligible built-in fields and active custom metadata fields of type `TEXT`:

```http
GET /api/projects/{projectId}/sample-vocabularies
```

```json
[
  {
    "id": "0d51436d-6a5e-4ad9-8212-e4f98986f15d",
    "fieldKey": "host_sex",
    "label": "Host sex",
    "custom": false,
    "terms": ["female", "male", "unknown"],
    "createdOn": "2026-08-11T09:00:00Z",
    "modifiedOn": "2026-08-11T09:30:00Z"
  },
  {
    "id": null,
    "fieldKey": "sample_status",
    "label": "Sample status",
    "custom": true,
    "terms": [],
    "createdOn": null,
    "modifiedOn": null
  }
]
```

Use `fieldKey` as the stable key in sample payloads and table/editor state; `label` is for display. `custom` distinguishes
custom metadata from built-in sample attributes. A null `id` means that the field is eligible but currently
unrestricted. The list should therefore drive client controls instead of maintaining a duplicate list of eligible
built-in fields.

A viewer can fetch one configured vocabulary with
`GET /api/projects/{projectId}/sample-vocabularies/{fieldKey}`. This endpoint returns `404` when no vocabulary is
configured; use the collection endpoint when the UI also needs unrestricted eligible fields.

### Managing a Vocabulary

Only project admins can configure vocabularies. `PUT` creates a vocabulary or atomically replaces its complete term
set:

```http
PUT /api/projects/{projectId}/sample-vocabularies/host_sex
Content-Type: application/json

{
  "terms": ["female", "male", "unknown"]
}
```

At least one term is required. Terms are trimmed by the server and must be nonblank and unique after trimming. To
remove a restriction rather than replace its values, use:

```http
DELETE /api/projects/{projectId}/sample-vocabularies/{fieldKey}
```

Deletion returns `204` and does not modify existing samples. Removing an individual term also preserves historical
values, but that value is rejected if submitted again later. Deleting the vocabulary makes the field unrestricted.

### Editor Behavior and Validation

- Render a select/autocomplete control when `id` is non-null, using `terms` as the allowed values. Keep an empty option
  when the underlying field is optional.
- Matching is exact and case-sensitive after surrounding whitespace is trimmed. For example, `" female "` matches
  `"female"`, while `"Female"` does not.
- Empty and whitespace-only strings are allowed so editors can clear a controlled field.
- Only active custom `TEXT` fields are eligible. Sample identifiers, sample names, numeric, boolean, date, timestamp,
  and derived fields are not controlled by this feature.
- Refresh the vocabulary list after an admin changes custom-field definitions or vocabulary configuration. Always let
  the API remain the source of truth, because another client can change the terms between loading and saving.

An out-of-vocabulary write returns HTTP `400` with a JSON array of structured violations:

```json
[
  {
    "sample": "sample-42",
    "fieldKey": "host_sex",
    "rejectedValue": "Female",
    "message": "Value is not in the configured vocabulary"
  }
]
```

Map each violation by `sample` and `fieldKey` to the corresponding editor cell or form control, and display `message`
to the user. `rejectedValue` is the original submitted value, before trimming. A direct create or update containing a
violation does not write the offending sample data.

Bulk patches and sample-sheet imports collect multiple violations in the same response. Invalid samples/rows are
skipped, while valid samples/rows are still processed even though the overall response is HTTP `400`; clients should
not interpret that status as a rollback of the entire bulk operation. CSV/TSV errors use canonical field keys even
when an accepted column alias was present in the uploaded file, and `sample` contains the sample name when available
(or row context when it is not).

## Tests

Run the test suite:

```shell
./mvnw test
```

Integration tests can be run with:

```shell
./mvnw verify
```

## Releases

Releases are automated from Conventional Commits pushed to `main`. Use these commit types to control the next version:

- `fix`: patch release, for example `0.1.0` to `0.1.1`.
- `feat`: minor release, for example `0.1.0` to `0.2.0`.
- A `BREAKING CHANGE:` footer or `!` after the type: major release, for example `0.1.0` to `1.0.0`.
- Other configured types, such as `docs`, `refactor`, `test`, `build`, `ci`, and `chore`, are included in the
  changelog but do not trigger a release by themselves.

Release Please maintains a release pull request that updates `pom.xml` and `CHANGELOG.md`. Merging that pull request
creates the matching Git tag and GitHub Release, then publishes the Quarkus image to
`ghcr.io/elixir-metatrack/api-server:<version>`. Release tags are unprefixed semantic versions, such as `0.2.0`,
consistent with the existing `0.1.0` tag. Ordinary pushes to `main` continue to publish branch, commit SHA, and
`latest` image tags.

Repository maintainers must enable **Settings > Actions > General > Workflow permissions > Allow GitHub Actions to
create and approve pull requests**. Branch protection for `main` must also allow the generated Release Please pull
request to follow the repository's normal merge process.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

---
*This project was generated using Quarkus.*
