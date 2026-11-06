#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

usage() {
  cat <<'USAGE'
Usage:
  ./dev/dev.sh up
  ./dev/dev.sh down
  ./dev/dev.sh reset
  ./dev/dev.sh api
  ./dev/dev.sh api --remote ENV_FILE

`up` starts local dependencies, waits for readiness, and creates the S3 bucket.
`down` stops the stack while preserving its data.
`reset` removes this Compose project's volumes after explicit confirmation.
`api` starts the local stack and runs Quarkus dev mode on the host.
`api --remote ENV_FILE` runs against explicitly configured remote services without starting Compose.
USAGE
}

load_environment() {
  local env_file=$1
  if [[ ! -f "$env_file" ]]; then
    printf 'Environment file not found: %s\n' "$env_file" >&2
    if [[ "$env_file" == "$PROJECT_ROOT/.env" ]]; then
      printf 'Create it with: cp .env.example .env\n' >&2
    fi
    exit 1
  fi

  set -a
  # Environment files are intentionally shell-compatible so the same values work in Compose and this launcher.
  # shellcheck source=/dev/null
  source "$env_file"
  set +a
}

require_local_configuration() {
  local expected_jdbc="jdbc:postgresql://localhost:${POSTGRES_HOST_PORT}/${POSTGRES_DB}"
  local expected_keycloak_base="http://localhost:${KEYCLOAK_HOST_PORT}"
  local expected_keycloak="${expected_keycloak_base}/realms/${KEYCLOAK_REALM}"
  local expected_s3="http://localhost:${SILO_API_HOST_PORT}"
  local expected_sftpgo="http://localhost:${SFTPGO_HTTP_HOST_PORT:-}"

  if [[ "${JDBC_STRING:-}" != "$expected_jdbc" \
      || "${KEYCLOAK_BASE_URL:-}" != "$expected_keycloak_base" \
      || "${KEYCLOAK_URL:-}" != "$expected_keycloak" \
      || "${S3_ENDPOINT:-}" != "$expected_s3" \
      || "${SFTPGO_API_URL:-}" != "$expected_sftpgo" \
      || "${SFTPGO_S3_ENDPOINT:-}" != "http://silo:9000" \
      || "${SFTPGO_ENABLED:-false}" != true ]]; then
    printf 'Local API mode only accepts the local PostgreSQL, Keycloak, Silo, and SFTPGo endpoints in .env.\n' >&2
    printf 'Update an existing .env with the SFTPGo entries from .env.example.\n' >&2
    printf 'For remote endpoints, use: ./dev/dev.sh api --remote ENV_FILE\n' >&2
    exit 1
  fi
  for name in SFTPGO_SFTP_HOST_PORT SFTPGO_HTTP_HOST_PORT SFTPGO_ADMIN_USERNAME \
      SFTPGO_ADMIN_PASSWORD SFTPGO_SFTP_HOST SFTPGO_SFTP_PORT SFTPGO_S3_REGION \
      SFTPGO_S3_ACCESS_KEY SFTPGO_S3_SECRET_KEY SFTPGO_S3_BUCKET_NAME \
      SFTPGO_S3_PATH_STYLE_ACCESS SFTPGO_HOOK_URL SFTPGO_HOOK_SECRET \
      UPLOAD_BATCH_STAGING_BUDGET_BYTES; do
    if [[ -z "${!name:-}" ]]; then
      printf 'Local SFTPGo configuration variable %s must be set in .env.\n' "$name" >&2
      exit 1
    fi
  done
  if [[ ! "${UPLOAD_BATCH_STAGING_BUDGET_BYTES}" =~ ^[0-9]+$ ]] \
      || (( UPLOAD_BATCH_STAGING_BUDGET_BYTES <= 0 )); then
    printf 'UPLOAD_BATCH_STAGING_BUDGET_BYTES must be a positive integer in .env.\n' >&2
    exit 1
  fi
}

require_remote_configuration() {
  local name
  for name in DB_USERNAME DB_PASSWORD JDBC_STRING KEYCLOAK_URL KEYCLOAK_BASE_URL \
      KEYCLOAK_REALM KEYCLOAK_ADMIN_CLIENT_ID KEYCLOAK_ADMIN_CLIENT_SECRET \
      S3_ENDPOINT S3_REGION S3_ACCESS_KEY S3_SECRET_KEY; do
    if [[ -z "${!name:-}" ]]; then
      printf 'Remote environment variable %s must be set.\n' "$name" >&2
      exit 1
    fi
  done
  if [[ "${SFTPGO_ENABLED:-false}" == true ]]; then
    for name in SFTPGO_API_URL SFTPGO_ADMIN_USERNAME SFTPGO_ADMIN_PASSWORD \
        SFTPGO_SFTP_HOST SFTPGO_SFTP_PORT SFTPGO_S3_ENDPOINT SFTPGO_S3_REGION \
        SFTPGO_S3_ACCESS_KEY SFTPGO_S3_SECRET_KEY SFTPGO_S3_BUCKET_NAME \
        SFTPGO_S3_PATH_STYLE_ACCESS SFTPGO_HOOK_URL SFTPGO_HOOK_SECRET \
        UPLOAD_BATCH_STAGING_BUDGET_BYTES; do
      if [[ -z "${!name:-}" ]]; then
        printf 'SFTPGo is enabled but remote environment variable %s is empty.\n' "$name" >&2
        exit 1
      fi
    done
    if [[ ! "${UPLOAD_BATCH_STAGING_BUDGET_BYTES}" =~ ^[0-9]+$ ]] \
        || (( UPLOAD_BATCH_STAGING_BUDGET_BYTES <= 0 )); then
      printf 'SFTPGo is enabled but UPLOAD_BATCH_STAGING_BUDGET_BYTES is not a positive integer.\n' >&2
      exit 1
    fi
  fi
}

compose() {
  docker compose --project-name metatrack-dev --env-file "$PROJECT_ROOT/.env" "$@"
}

start_local_stack() {
  load_environment "$PROJECT_ROOT/.env"
  require_local_configuration
  if ! command -v curl >/dev/null 2>&1; then
    printf 'curl is required to wait for the local Keycloak realm to become ready.\n' >&2
    exit 1
  fi
  compose up --detach --wait postgres keycloak silo
  local discovery_url="${KEYCLOAK_URL}/.well-known/openid-configuration"
  local attempt
  for attempt in {1..60}; do
    if curl --fail --silent "$discovery_url" >/dev/null; then
      break
    fi
    if [[ "$attempt" -eq 60 ]]; then
      printf 'Keycloak did not become ready at %s.\n' "$discovery_url" >&2
      compose logs keycloak >&2
      exit 1
    fi
    sleep 2
  done
  compose run --rm --no-deps silo-bootstrap
  compose up --detach --wait sftpgo
  local sftpgo_health_url="${SFTPGO_API_URL}/healthz"
  local attempt
  for attempt in {1..30}; do
    if curl --fail --silent "$sftpgo_health_url" >/dev/null; then
      break
    fi
    if [[ "$attempt" -eq 30 ]]; then
      printf 'SFTPGo did not become ready at %s.\n' "$sftpgo_health_url" >&2
      compose logs sftpgo >&2
      exit 1
    fi
    sleep 2
  done
}

start_api() {
  local remote_env=${1:-}
  if [[ -z "$remote_env" ]]; then
    start_local_stack
    export QUARKUS_PROFILE=dev
    export QUARKUS_FLYWAY_MIGRATE_AT_START=true
  else
    if [[ "$remote_env" != /* ]]; then
      remote_env="$PROJECT_ROOT/$remote_env"
    fi
    load_environment "$remote_env"
    require_remote_configuration
    export QUARKUS_PROFILE=dev,remote-dev
    export QUARKUS_FLYWAY_MIGRATE_AT_START=false
    printf 'Remote API mode: Flyway startup migrations are disabled; API requests can still modify remote data.\n' >&2
  fi

  exec ./mvnw quarkus:dev
}

case "${1:-}" in
  up)
    [[ $# -eq 1 ]] || { usage >&2; exit 2; }
    start_local_stack
    ;;
  down)
    [[ $# -eq 1 ]] || { usage >&2; exit 2; }
    compose down
    ;;
  reset)
    [[ $# -eq 1 ]] || { usage >&2; exit 2; }
    printf 'This permanently deletes the PostgreSQL, Keycloak, Silo, and SFTPGo volumes for Compose project metatrack-dev.\n'
    read -r -p 'Type "reset" to continue: ' confirmation
    if [[ "$confirmation" != reset ]]; then
      printf 'Reset cancelled.\n'
      exit 1
    fi
    compose down --volumes
    ;;
  api)
    if [[ $# -eq 1 ]]; then
      start_api
    elif [[ $# -eq 3 && "$2" == --remote ]]; then
      start_api "$3"
    else
      usage >&2
      exit 2
    fi
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac