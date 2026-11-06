# Manifest-based SFTP batch uploads

This runbook describes the API-first workflow using the pinned self-hosted SFTPGo Open Source image. SFTPGo accepts
public-key-only accounts scoped to one batch's S3 staging prefix; the API validates and links files only after the
batch is frozen, active transfers drain, and each staged object passes verification.

## Authorization and lifecycle

- Register a batch as an authenticated user with current `EDITOR`, `ADMIN`, or `OWNER` membership in the requested
  project. A sub-project upload also requires `EDITOR`-or-higher membership in the owning parent project.
- The manifest resolves `sampleName` and `assayId` to existing records. It never creates or infers scientific records.
- The creator can inspect the detailed report. Current project `ADMIN`/`OWNER` members can also inspect it. Finalization
  and publication recheck the original uploader's current upload rights; administrative access does not bypass that
  check.
- The API returns a unique SFTP username and expiration. No SFTP password or private SSH key is issued or stored.
- `POST .../{batchId}/finalize` atomically stops new admissions. Already-admitted transfers may finish into staging;
  durable drain polling uses SFTPGo's active-connection transfer list before processing begins.
- Ingestion is per entry. A failed/missing/corrected file is submitted in a new batch; a frozen batch is never reopened.
  `DELETE .../{batchId}` cancels without publishing and schedules retained-data cleanup.

## CSV manifest

The upload part is a UTF-8 CSV file (a UTF-8 BOM is accepted). Required columns are `relativePath`, `sampleName`,
`assayId`, `sizeBytes`, and `sha256`; `readRole` is optional. See
[`upload-batch-manifest.example.csv`](upload-batch-manifest.example.csv).

- `relativePath` is relative to the batch SFTP root, uses `/`, contains no empty, `.` or `..` path segments, and is
  unique in the manifest. Paths are not inferred from filenames.
- `sampleName` must resolve in the requested project scope. `assayId` is the UUID of an existing assay associated with
  that sample and visible in the requested project scope.
- `sizeBytes` is the exact expected byte count. The configured hard ceiling is 50 GiB per file and 100 files per batch.
- `sha256` is 64 hexadecimal characters. It is verified while streaming the staged object. Existing declared MD5 and
  `readRole` values on a compatible `PENDING` file are preserved and verified; they are never silently replaced.
- `readRole` values are `SINGLE`, `FORWARD`, or `REVERSE`. If omitted for an existing `PENDING` file, its current role is
  retained.
- Registration is all-or-nothing for manifest validity. File-level ingestion after finalization is partial-success.

The zero hash in the CSV template is a placeholder; calculate the actual checksum for each source file before
registration.

## Register, transfer, finalize, and poll

Generate a key pair locally. Send only the public key to the API:

```shell
ssh-keygen -t ed25519 -f "$HOME/.ssh/metatrack-batch"
```

Set `API_TOKEN`, `PROJECT_ID`, and `MANIFEST` for the authenticated API session and prepared CSV:

```shell
PUBLIC_KEY="$(<"$HOME/.ssh/metatrack-batch.pub")"
curl --fail-with-body --silent --show-error \
  --request POST \
  --header "Authorization: Bearer $API_TOKEN" \
  --form "manifest=@$MANIFEST;type=text/csv" \
  --form-string "sshPublicKey=$PUBLIC_KEY" \
  "http://localhost:1234/api/projects/$PROJECT_ID/upload-batches"
```

A successful response contains `batchId`, `status`, `sftpUsername`, `sftpHost`, `sftpPort`, `expiresAt`, and per-entry
details. A `201` means account provisioning completed; `202` means registration committed and recoverable SFTPGo
provisioning is retrying. Do not retry registration blindly after a `202`; poll the returned batch ID.

Connect with the private key that remains on the user's machine:

```shell
sftp -i "$HOME/.ssh/metatrack-batch" -P "$SFTP_PORT" "$SFTP_USERNAME@$SFTP_HOST"
```

For the example manifest, transfer into the batch root using its declared relative paths:

```text
sftp> mkdir reads
sftp> put ./sample-a_R1.fastq reads/sample-a_R1.fastq
sftp> put ./sample-a_R2.fastq reads/sample-a_R2.fastq
```

If a client transfer is interrupted, resume the same listed file with an SFTP client's resume command. OpenSSH's
`reput` form is:

```text
sftp> reput ./sample-a_R1.fastq reads/sample-a_R1.fastq
```

SFTPGo's S3 backend accepts only declared paths through the authenticated synchronous API hook. Batch accounts can
list their staging root and create directories, but cannot download, delete, rename, copy, use SCP, or access another
batch's S3 prefix.

Freeze and finalize after all transfers have stopped or the active transfers have had time to drain:

```shell
curl --fail-with-body --silent --show-error \
  --request POST \
  --header "Authorization: Bearer $API_TOKEN" \
  "http://localhost:1234/api/projects/$PROJECT_ID/upload-batches/$BATCH_ID/finalize"

curl --fail-with-body --silent --show-error \
  --header "Authorization: Bearer $API_TOKEN" \
  "http://localhost:1234/api/projects/$PROJECT_ID/upload-batches/$BATCH_ID"
```

Finalize is idempotent and returns `202` while drain/processing is underway. Poll the status endpoint for per-entry
`status`, `bytesTransferred`, `attempts`, and `error`. To cancel before publication:

```shell
curl --fail-with-body --silent --show-error \
  --request DELETE \
  --header "Authorization: Bearer $API_TOKEN" \
  "http://localhost:1234/api/projects/$PROJECT_ID/upload-batches/$BATCH_ID"
```

Missing files, checksum corrections, or other manifest changes require a new batch. Failed, cancelled, and expired
staging data is retained for seven days and continues consuming the staging budget until cleanup actually completes.

## Limits, resume, and storage costs

The checked-in defaults are 2 active transfers per batch, 8 across all batches, 2 ingestion workers, a seven-day upload
window, a seven-day unsuccessful-data retention period, and a 1 TiB local example staging budget. Operators can lower
these values; hard ceilings remain 100 files and 50 GiB per file. Each SFTPGo account receives the batch's declared
byte/file quota, while the API's synchronous hook owns the per-batch/global concurrent-transfer admission because OSS
does not provide the newer built-in global transfer cap.

S3-backed resume can send only the remaining bytes from the SFTP client, but SFTPGo must reread and rewrite the object
on the SFTPGo-to-S3 side. This storage/network cost is accepted. `SFTPGO_COMMON__UPLOAD_MODE=6` enables atomic resume and
retains S3 partials after client errors; `SFTPGO_COMMON__RESUME_MAX_SIZE` is set to the configured 50 GiB ceiling.
OSS uses writable, file-backed transfer pipes in each user's home directory; the Compose `sftpgo-pipe` volume is
temporary pipe space, not durable staging. Size production pipe storage for the configured concurrency: 8 concurrent
50 GiB transfers can require roughly 400 GiB of temporary capacity, plus headroom.

SFTPGo's quota tracker is enabled with `track_quota=2`. A failed or cancelled batch still consumes its reserved staging
budget until the version-aware S3 cleanup worker has purged its staging object versions and abandoned multipart uploads.
Successful staging copies are removed only after the verified final object and database link commit.

## Production setup and security

Use the pinned `drakkan/sftpgo:v2.7.6` Open Source image digest in `compose.yaml` as the compatibility baseline. Run a
single SFTPGo OSS instance; do not horizontally scale the OSS service for this workflow. Persist SFTPGo control data and
SSH host keys. Provide a separate writable, adequately sized temporary-pipe filesystem. Expose SFTP on the required TCP
endpoint, but keep the SFTPGo HTTP/WebAdmin management API private and authenticated.

Configure these values through the deployment's secret manager/environment, never in committed `.env` files:

- SFTPGo administrator username/password, API URL, SFTP host/port, and the API hook URL.
- A high-entropy `SFTPGO_HOOK_SECRET` shared by the API and the management action. SFTPGo receives the secret in its
  encrypted secret envelope and stores it encrypted at rest; the API also checks Basic authentication independently
  from end-user OIDC.
- Staging-only `SFTPGO_S3_ACCESS_KEY` / `SFTPGO_S3_SECRET_KEY`, separate from API-managed final-object credentials.
  Scope this identity to `staging/*` only; the SFTPGo user `key_prefix` is an additional isolation layer, not a
  substitute for the S3 policy.

An AWS-style starting policy for the staging identity is:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:ListBucket", "s3:ListBucketMultipartUploads"],
      "Resource": "arn:aws:s3:::BUCKET",
      "Condition": {"StringLike": {"s3:prefix": ["staging/*"]}}
    },
    {
      "Effect": "Allow",
      "Action": ["s3:AbortMultipartUpload", "s3:DeleteObject", "s3:GetObject",
                  "s3:GetObjectAttributes", "s3:ListMultipartUploadParts", "s3:PutObject"],
      "Resource": "arn:aws:s3:::BUCKET/staging/*"
    }
  ]
}
```

Verify the actual required operations against the selected S3-compatible provider before production deployment. The API
identity separately requires permission to stream/read staging data, write the API-managed final prefix, perform
server-side multipart copies, remove staged object versions, and abort abandoned multipart uploads. Keep the SFTPGo
management channel and webhook on authenticated private connectivity (TLS in production); never publish the admin API
or hook credentials to SFTP clients. Host keys must remain stable across container restarts.

## Compatibility testing

### Disposable small-object compatibility run

Run from the repository root in Bash with Docker Compose, OpenSSH (`sftp`, `ssh-keygen`), and Java 25 available through
the Maven wrapper. Use a new, disposable project, never a shared development or production SFTPGo/S3 instance. The
opt-in Java runner overwrites the `metatrack-batch-upload-admission` action and admission/completion rules and removes
legacy `metatrack-compat-hook` actions/rules (including rules referencing that legacy action). It deletes its temporary
users, but leaves objects under `staging/sftpgo-compat-*` and leaves the installed hook configuration behind.

In each terminal used below, load the example environment and override ports so this project does not collide with
the normal development stack. These are local-only example credentials; do not source an untrusted environment file.
Ensure ports 29000, 29001, 22022, 28082, and 22345 are free first:

```shell
set -a
. ./.env.example
set +a
export COMPOSE_PROJECT_NAME=metatrack-sftpgo-compat
export SILO_API_HOST_PORT=29000 SILO_CONSOLE_HOST_PORT=29001
export SFTPGO_SFTP_HOST_PORT=22022 SFTPGO_HTTP_HOST_PORT=28082
export S3_ENDPOINT=http://localhost:29000
export SFTPGO_API_URL=http://localhost:28082
export SFTPGO_SFTP_HOST=localhost SFTPGO_SFTP_PORT=22022
export SFTPGO_S3_ENDPOINT=http://silo:9000
export SFTPGO_HOOK_SECRET=disposable-compatibility-hook-secret-at-least-32-characters
export SFTPGO_COMPATIBILITY_HOOK_PORT=22345
export SFTPGO_COMPATIBILITY_HOOK_DOCKER_HOST=host.docker.internal
```

The host-side Java S3 test uses `S3_ENDPOINT`; SFTPGo's container-side backend uses `SFTPGO_S3_ENDPOINT`.
Start only the disposable storage and SFTPGo services, then create the bucket:

```shell
docker compose --env-file .env.example -p "$COMPOSE_PROJECT_NAME" up -d --wait silo sftpgo
docker compose --env-file .env.example -p "$COMPOSE_PROJECT_NAME" run --rm silo-bootstrap
```

The opt-in Java test starts and stops its hook in-process, bound to all interfaces so SFTPGo can reach it through
Compose's `host.docker.internal` host-gateway mapping. Restrict host firewall access to this disposable Docker network:
the health endpoint is unauthenticated, and the authenticated reservation-release endpoint is test-only. The runner
requires a hook secret of at least 32 characters. In the first terminal, run the checks:

```shell
./mvnw test -Dtest=SftpGoCompatibilityIntegrationTest -Dmetatrack.sftpgo.compatibility.disposable=true
./mvnw test -Dtest=S3ObjectStorageSiloIntegrationTest -Dmetatrack.s3.integration=true
```

The Java runner checks public-key login, encrypted stored hook credentials, synchronous 2-per-batch/8-global admission,
connection transfer paths/byte fields, and a 16 MiB interrupted upload followed by `reput` and SHA-256 readback. Its
resume user has test-only download permission; it explicitly releases the interrupted reservation through the test
hook. This is not proof of real API authorization, manifest enforcement, durable drain, or ingestion. It also disables
SSH host-key checking for its disposable users; do not copy that setting into production clients.
The opt-in `S3ObjectStorageSiloIntegrationTest` separately checks small copy and an 11 MiB, three-part server-side copy,
recovered part listing, metadata/hash readback, and orphan multipart abort. Neither check exercises a 50 GiB object.

After saving results, the test process shuts down its embedded hook automatically. Remove only this disposable project's
volumes:

```shell
docker compose --env-file .env.example -p "$COMPOSE_PROJECT_NAME" down --volumes --remove-orphans
```

### Explicit 50 GiB interrupted-transfer acceptance (manual, resource-intensive)

This is an opt-in operator acceptance run, not part of the default suite or an option implemented by the Java
small-object runner. Use a fresh disposable full API/PostgreSQL/OIDC/SFTPGo/Silo deployment with real API hooks, not the
compatibility hook. Destroy the small-run stack first so its replacement event rules cannot intercept API admissions.
Configure the API's host-side endpoints and SFTPGo's container-side endpoint for that deployment, a reachable authenticated
`SFTPGO_HOOK_URL`, and a fresh shared hook secret. Provision a disposable project, sample, assay, and editor account.
Keep the 50 GiB ceiling/resume limit and 16 MiB multipart-copy part size from `.env.example`; record all overrides.

Reserve at least 50 GiB for the source, over 50 GiB of free SFTPGo pipe capacity for this single transfer, and ample
S3 capacity for partials, staging, final objects, and any retained versions (allow at least 150 GiB plus headroom).
The run reads/writes hundreds of GiB across fixture generation, resume, verification, promotion, and final readback;
budget hours, network charges, and disk monitoring. Run only one large transfer initially. Do not extrapolate its
success to eight concurrent transfers without separately provisioning/testing the roughly 400 GiB pipe requirement.

1. Create a non-sparse source and record its exact size/hash. The following writes 53,687,091,200 bytes; do not use
   `truncate` or a small fixture as a substitute. Keep the fixture unchanged until final readback completes:

   ```shell
   mkdir -p acceptance-50g
   dd if=/dev/urandom of=acceptance-50g/source.bin bs=16M count=3200 iflag=fullblock status=progress
   stat --format='%s' acceptance-50g/source.bin
   sha256sum acceptance-50g/source.bin | tee acceptance-50g/source.sha256
   ```

2. Register a one-entry manifest through the API workflow above with `relativePath=source.bin`, the disposable
   sample/assay, `sizeBytes=53687091200`, and the recorded SHA-256. Save the batch ID, staging key/prefix, returned
   SFTP endpoint/username, and initial report. Use a key held locally and verify the disposable server's host key.
3. Begin `put acceptance-50g/source.bin source.bin` with OpenSSH SFTP. If needed, use `-l 65536` (Kbit/s) to leave
   time to interrupt. While transferring, capture authenticated SFTPGo `GET /api/v2/connections` output showing the
   batch username, active path, and increasing byte count. Interrupt the client before completion (Ctrl-C, then
   exit); do not finalize yet. Wait for the connection/transfer to disappear and the real API reservation to drain.
   Require a retained partial with `0 < size < 53687091200`, visible via SFTP `ls -l source.bin` and S3 HEAD/listing.
   Save the observed size and storage evidence. A vanished partial or stuck reservation is a failure, not a reason
   to use the compatibility hook's test-only manual release endpoint.
4. Reconnect as the same batch user and run `reput acceptance-50g/source.bin source.bin`. Require successful resume
   and an eventual staged size of exactly 53,687,091,200 bytes. Record elapsed time and client/backend I/O separately:
   only the remaining client bytes may be sent, while SFTPGo rereads/rewrites the retained S3 object. Do not treat
   backend rewrite traffic as failed resume. Capture pipe usage and ensure no disk exhaustion.
5. Finalize using the API and poll the detailed batch report to a terminal outcome. Capture the freeze/drain
   transition and confirm no new transfer is admitted after freeze. Require successful entry ingestion with the
   declared byte count, a linked final file, and no checksum error. Observe API memory during streaming SHA-256
   verification: it must not grow with the full object size. Save reports and logs without credentials.
6. Require storage audit/trace evidence of `CreateMultipartUpload`, ranged `UploadPartCopy`, and
   `CompleteMultipartUpload` for the API-managed final key, rather than a single-object copy or an API download and
   re-upload. At the default 16 MiB part size this exact fixture needs 3,200 parts; verify contiguous source ranges
   cover all bytes. Poll multipart listings while promotion is running and confirm no upload remains for that final
   key after completion. Enable provider tracing before finalization; an empty listing afterwards alone is not proof
   of multipart promotion. Do not use a multipart ETag as a SHA-256 checksum.
7. Using API/operator read credentials (never granting the batch user download permission), stream the final object
   through SHA-256 and compare it to `acceptance-50g/source.sha256`. For example, with AWS CLI configured for the
   disposable S3 identity and `FINAL_KEY` taken from the linked file/storage record:

   ```shell
   set -o pipefail
   export AWS_ACCESS_KEY_ID="$S3_ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$S3_SECRET_KEY"
   export AWS_DEFAULT_REGION="$S3_REGION"
   aws --endpoint-url "$S3_ENDPOINT" s3api head-object --bucket "$S3_BUCKET_NAME" --key "$FINAL_KEY"
   aws --endpoint-url "$S3_ENDPOINT" s3 cp "s3://$S3_BUCKET_NAME/$FINAL_KEY" - --only-show-errors \
     | sha256sum | tee acceptance-50g/final.sha256
   test "$(cut -d ' ' -f1 acceptance-50g/source.sha256)" = "$(cut -d ' ' -f1 acceptance-50g/final.sha256)"
   ```

   Require HEAD size 53,687,091,200 and identical source/final hashes. Configure the CLI for the provider's required
   path-style access if necessary. Capture evidence that successful staging data is removed after publication and
   the linked final object remains readable. List versions as well as current keys if versioning is enabled.
8. Save image digest, configuration/part size, batch/entry reports, source/final hashes, interrupted size, transfer
   snapshots, multipart trace, timings, peak memory/pipe usage, and cleanup observations outside disposable volumes.
   Any missing evidence is an unverified acceptance criterion. Tear down only the named disposable stack and its
   volumes after inspection; remove only the fixture/evidence files created for this run when no longer needed.
   Cancelled/failed staging normally retains its reservation/data for seven days, so do not claim immediate cleanup
   from cancellation alone. Record unsuccessful runs explicitly and rerun with a new batch after correcting issues.