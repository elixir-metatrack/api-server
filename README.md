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
├── invitation/ # Immediate invitation email delivery and resend coordination
├── notification/ # Persistent recipient inbox and post-login invitation claiming
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

## Email Invitations and Notifications

Project `ADMIN` and `OWNER` members can invite an email address with `VIEWER`, `EDITOR`, or `ADMIN` access,
never above their own authority. Invitations cannot grant `OWNER`. Both registered and unregistered recipients
receive email naming the inviter and project. No account is created and no membership is granted until acceptance.
Existing direct-membership and user-initiated join-request APIs remain separate and compatible.

### SPA API Contract

Every route requires the SPA's normal bearer access token. Project administration routes also require project
administrator authority. Request and response schemas are published in OpenAPI at `/q/openapi`.

| Method and path | Behavior |
|---|---|
| `POST /api/projects/{projectId}/invitations` | Send `{ "email": "person@example.org", "role": "VIEWER" }`; returns `201` with the saved invitation and delivery outcome, even if SMTP fails |
| `GET /api/projects/{projectId}/invitations?page=0&size=20` | Paginated administrative invitation list, including delivery outcomes |
| `POST /api/projects/{projectId}/invitations/{id}/resend` | Explicit delivery attempt for a pending invitation; rechecks registration without extending expiry |
| `DELETE /api/projects/{projectId}/invitations/{id}` | Revoke a pending invitation |
| `POST /api/invitations/{id}/accept` | Recipient-only acceptance; creates membership transactionally |
| `POST /api/invitations/{id}/decline` | Recipient-only decline; does not create membership |
| `POST /api/notifications/sync` | Materialize eligible invitations using verified token identity; no email input |
| `GET /api/notifications?page=0&size=20&unread=true` | Private paginated inbox, including total unread count and invitation actionability |
| `PATCH /api/notifications/{id}` | Send `{ "read": true }` or `{ "read": false }`; changes only the caller's read state |

Pagination uses zero-based pages and sizes from 1 to 100. Omit `unread` for all notifications; `false` selects read
notifications. Treat display content as text, not HTML. Read state and invitation decisions are independent.

After each successful sign-in, call synchronization, then load the inbox. A new recipient must complete registration,
verify their email, and obtain a fresh access token containing verified email claims before synchronization can claim
an invitation. Registration alone does not join a project. Synchronization is idempotent and never accepts an email
address from the browser. Existing-account invitations are bound to the original Keycloak subject and cannot be
claimed by a different account with the same email. Acceptance and decline also require a verified matching email.

Use the returned invitation status/actionability to render actions, then refresh the inbox and project memberships
after a decision. Server-side authorization remains decisive if state changes after listing. Repeating the same
terminal decision succeeds; switching decisions fails. Existing membership roles are never replaced by acceptance.
An inviter who loses sufficient authority can no longer grant access through an outstanding invitation.

Invitation states are `PENDING`, `ACCEPTED`, `DECLINED`, `REVOKED`, and `EXPIRED`. Expiry is enforced on requests,
without a scheduled worker. Self-invitations, existing members, and duplicate active project/email invitations are
rejected. A fresh invitation can be created after expiry, decline, or revocation. Email matching trims whitespace and
normalizes case without provider-specific alias rewriting. Responses do not reveal whether the recipient is registered.

Errors: `400` invalid input/role/pagination; `401` unauthenticated; `403` insufficient authority or missing verified-email
context; `404` inaccessible recipient resources; `409` duplicate, expired, or conflicting decision; `429` resend cooldown.
Keycloak lookup failures use the existing upstream-error mapping and are never interpreted as an unregistered user.

### Delivery and Resend UX

Invitations and existing-account notifications commit before SMTP is attempted. Delivery is immediate, outside the
database transaction, with no queue, automatic retries, or real-time push. Preserve the invitation ID after `201`:
do not repeat creation to recover from mail failure; offer an explicit resend action instead.

Responses include `deliveryStatus`, `deliveryAttemptedOn`, `deliveryCompletedOn`, and `deliveryRetryAfter`:

- `NOT_ATTEMPTED`: no delivery attempt has been claimed.
- `SENDING`: an attempt is in progress.
- `SENT`: SMTP accepted the message; this does not confirm inbox delivery.
- `FAILED`: a known pre-send failure prevented delivery.
- `UNKNOWN`: delivery could not be confirmed, including SMTP errors/timeouts or an abandoned attempt.

Respect `deliveryRetryAfter` before offering resend. Unresolved attempts become uncertain after the sending timeout;
late results cannot overwrite newer attempts. Explicitly retrying uncertain delivery may send a duplicate email:
SMTP cannot provide exactly-once delivery. Resend checks registration again, so a newly registered user receives the
sign-in variant. Neither delivery failure nor resend extends the original invitation expiry.

### Keycloak and SPA Prerequisites

- Enable realm self-registration and email verification; configure Keycloak's own verification-email transport.
- Enforce unique email addresses in the realm. Ambiguous exact-email search results are rejected, not guessed.
- Include `email` and boolean `email_verified` claims in API access tokens, and retain the stable UUID `sub` claim.
- Grant the existing confidential admin client's service account only the realm user-search/read permissions needed
  for exact email and ID lookup (for example the applicable `query-users`/`view-users` permissions or fine-grained
  equivalent); do not grant user-creation or realm-administration privileges solely for invitations.
- Configure approved SPA redirect URIs and normal authorization-code flow with PKCE and OIDC state validation.
- Provide trusted SPA `/login` and `/register` entry routes. The registration route initiates Keycloak registration
  through the SPA's OIDC library and returns to the inbox after authentication. Do not use a static Keycloak
  authorization URL missing state/PKCE. No bearer invitation token or client-supplied redirect is used.

### SMTP and Invitation Configuration

Production requires the following environment-backed settings in `application.yml`:

| Variable | Meaning / default |
|---|---|
| `SMTP_FROM` | Sender email address |
| `SMTP_HOST` | SMTP server hostname |
| `SMTP_PORT` | SMTP submission port; `587` |
| `SMTP_USERNAME` | SMTP authentication user |
| `SMTP_PASSWORD` | SMTP secret; inject through deployment secret management |
| `INVITATION_LOGIN_URL` | Absolute HTTPS SPA sign-in entry URL |
| `INVITATION_REGISTRATION_URL` | Absolute HTTPS SPA registration entry URL |
| `INVITATION_EXPIRY` | Invitation lifetime; `P7D` |
| `INVITATION_RESEND_COOLDOWN` | Cooldown after completed attempts; `PT60S` |
| `INVITATION_SENDING_TIMEOUT` | Unresolved-attempt timeout; `PT5M`, at least the positive cooldown |

Entry URLs must not contain credentials, query strings, or fragments. Production requires authenticated STARTTLS,
certificate/hostname verification, and disables mock delivery; mailer timeout is 30 seconds. Development and tests
mock mail, with SPA entry defaults `http://localhost:3000/login` and `http://localhost:3000/register`.
Do not log raw recipient emails, SMTP credentials, access tokens, or Keycloak response bodies. Apply Flyway migrations
before serving the new endpoints; project deletion cascades to invitation-linked notifications.

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

Invitation/notification PostgreSQL tests are opt-in. Point them only at a disposable test database using
`INVITATION_TEST_JDBC_URL`, `INVITATION_TEST_DB_USER`, and `INVITATION_TEST_DB_PASSWORD`, then run `./mvnw test`.
These tests exercise Flyway, transactional acceptance, concurrent decisions/delivery claims, notification deduplication,
rollback, and deletion cleanup with mocked Keycloak and SMTP; without the variables the database tests are skipped.
Never point this test configuration at production data.

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
