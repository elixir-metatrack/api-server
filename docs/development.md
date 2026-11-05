# Local Development Environment

The checked-in Compose stack makes the API's supporting services reproducible across Linux, macOS, and Windows through
WSL2, on x86-64 and ARM64. Docker Compose manages PostgreSQL, Keycloak, and Silo; run the Java 25 API on the host for
IDE debugging and Quarkus live reload. Install `curl` on the host for the launcher's Keycloak readiness check.

## Start the local stack

Create the ignored local environment file and launch the API:

```shell
cp .env.example .env
./dev/dev.sh api
```

The API command waits for the dependencies to become ready and creates the S3 bucket if it does not exist. To start
the dependencies without starting the API, use `./dev/dev.sh up`. API access is at `http://localhost:1234`. The
default Keycloak realm issuer is `http://localhost:18081/realms/metatrack`, and the Silo console is
`http://localhost:19001`. The local S3 endpoint is `http://localhost:19000`; PostgreSQL is published at
`localhost:15432`. These nonstandard defaults avoid common ports used by existing local installations.

The SPA client is named `metatrack-spa` and permits redirects from `http://localhost:3000/*` with PKCE S256. The client
for the scalar API is `metatrack-scalar` and permits redirects from `http://localhost:1234/*` with PKCE S256. The
seeded, email-verified Keycloak users are:

| Username | Password | Email |
|---|---|---|
| `dev-admin` | `dev-admin-password` | `dev-admin@example.test` |
| `dev-user` | `dev-user-password` | `dev-user@example.test` |

The Keycloak admin console account and all service/database credentials are development-only values in `.env.example`.
They must not be reused outside this local stack. The API mailer is mocked in dev mode; this does not configure
Keycloak's own email transport. The seeded users are already verified. Realm import runs only when the realm does not
exist, so edits to `dev/keycloak/metatrack-realm.json` will not overwrite an existing realm; use the explicit reset
procedure to apply seed changes.

After importing the realm, verify that the API service account can persist the custom profile attributes with:

```shell
set -a
source .env
set +a
python3 dev/check-keycloak-profile.py
```

The check performs an Admin API `PUT` followed by a `GET` for all three fields, then restores the seeded user's
original profile. It refuses to run against non-localhost Keycloak endpoints.

The application database starts with Flyway migrations only; no project or file fixtures are created. Taxonomy API
calls remain external and use `TAXON_API_URL` (default `http://localhost:8080`). Features that need taxonomy data
still require a reachable taxonomy service.

## Stack lifecycle

```shell
./dev/dev.sh up
./dev/dev.sh down
./dev/dev.sh reset
```

`down` stops containers but keeps the named PostgreSQL, Keycloak, and Silo volumes. `reset` asks for the literal
confirmation `reset` before running Compose with `--volumes`; it irreversibly removes only the `metatrack-dev`
Compose project's data. Reset is needed to re-import a changed Keycloak realm seed. Do not use
`docker compose down -v` against another Compose project.

Host ports are configurable in `.env` (`POSTGRES_HOST_PORT`, `KEYCLOAK_HOST_PORT`, `SILO_API_HOST_PORT`, and
`SILO_CONSOLE_HOST_PORT`). The matching JDBC, Keycloak, and S3 URLs in that same file reference those variables. Keep
the endpoint variables local in `.env`; `./dev/dev.sh api` refuses non-local database, Keycloak, or S3 endpoints.

## Explicit remote development

Use a different private environment file for trusted remote endpoints. It may target different database, Keycloak,
and storage environments independently:

```shell
cp .env.remote.example .env.remote
# Set the endpoints and credentials in .env.remote.
./dev/dev.sh api --remote .env.remote
```

The remote command does not start Compose and activates the `remote-dev` Quarkus profile, which disables Flyway
startup migrations. Migration suppression is not read-only protection: API requests can still create, update, or
delete remote data. Carefully check every endpoint and credential before starting; do not use production data for
tests that write data.

Both `.env` and `.env.*` files are ignored by Git; the checked-in `.env.example` and `.env.remote.example` are
templates only. The remote environment file is shell-compatible because the launcher sources it before starting
Maven. Never commit actual credentials or export a production Keycloak realm into the local seed.

## Troubleshooting

- If a host port is occupied, choose another port in `.env`; its corresponding application URL is expanded from the
  same setting.
- If a database, Keycloak realm, or bucket looks stale, `./dev/dev.sh down` preserves it. `./dev/dev.sh reset` deletes
  the local volumes and the next `up` bootstraps them again.
- If Keycloak or Silo is not ready, inspect `docker compose logs keycloak silo postgres` and retry
  `./dev/dev.sh up` after resolving the reported startup issue.
- Invitation API emails are logged/mocked by the Quarkus dev profile. Keycloak email verification messages are
  separate; the pre-seeded accounts can be used without sending them.