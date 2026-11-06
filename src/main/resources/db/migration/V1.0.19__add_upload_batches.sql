CREATE TABLE upload_batch (
    id uuid NOT NULL PRIMARY KEY,
    requested_project_id bigint NOT NULL REFERENCES project(id),
    owning_project_id bigint NOT NULL REFERENCES project(id),
    uploader_id uuid NOT NULL,
    sftp_username varchar(128) NOT NULL UNIQUE,
    sftp_public_key text NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN (
        'PROVISIONING', 'OPEN', 'DRAINING', 'PROCESSING', 'SUCCEEDED',
        'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED', 'EXPIRED')),
    expires_at timestamp with time zone NOT NULL,
    reserved_bytes bigint NOT NULL CHECK (reserved_bytes >= 0),
    provisioning_attempts integer NOT NULL DEFAULT 0 CHECK (provisioning_attempts >= 0),
    provisioning_lease_until timestamp with time zone,
    last_error text,
    frozen_at timestamp with time zone,
    cleanup_after timestamp with time zone,
    staging_cleanup_completed_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

CREATE INDEX idx_upload_batch_project_created
    ON upload_batch (requested_project_id, created_at DESC);
CREATE INDEX idx_upload_batch_uploader_created
    ON upload_batch (uploader_id, created_at DESC);
CREATE INDEX idx_upload_batch_status_expiry
    ON upload_batch (status, expires_at);

CREATE TABLE upload_batch_entry (
    id uuid NOT NULL PRIMARY KEY,
    batch_id uuid NOT NULL REFERENCES upload_batch(id) ON DELETE CASCADE,
    manifest_row integer NOT NULL CHECK (manifest_row > 0),
    relative_path varchar(1024) NOT NULL,
    sample_id uuid NOT NULL REFERENCES sample(id),
    assay_id uuid NOT NULL REFERENCES assay(id),
    file_name varchar(255) NOT NULL,
    staging_key varchar(1024) NOT NULL,
    destination_virtual_path varchar(255) NOT NULL,
    destination_object_key varchar(1024) NOT NULL UNIQUE,
    expected_size_bytes bigint NOT NULL CHECK (expected_size_bytes >= 0),
    expected_sha256 varchar(64) NOT NULL CHECK (expected_sha256 ~ '^[0-9A-Fa-f]{64}$'),
    expected_md5 varchar(255),
    expected_unencrypted_md5 varchar(255),
    read_role varchar(32) CHECK (read_role IS NULL OR read_role IN ('SINGLE', 'FORWARD', 'REVERSE')),
    status varchar(32) NOT NULL CHECK (status IN (
        'REGISTERED', 'UPLOADING', 'VALIDATING', 'PROMOTING', 'COMMITTED', 'FAILED')),
    bytes_transferred bigint NOT NULL DEFAULT 0 CHECK (bytes_transferred >= 0),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_retry_at timestamp with time zone,
    processing_lease_until timestamp with time zone,
    source_etag text,
    source_version_id text,
    last_error text,
    promoted_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT uq_upload_batch_entry_row UNIQUE (batch_id, manifest_row),
    CONSTRAINT uq_upload_batch_entry_path UNIQUE (batch_id, relative_path),
    CONSTRAINT uq_upload_batch_entry_virtual_path UNIQUE (batch_id, destination_virtual_path)
);

CREATE INDEX idx_upload_batch_entry_batch_status
    ON upload_batch_entry (batch_id, status, manifest_row);
CREATE INDEX idx_upload_batch_entry_retry
    ON upload_batch_entry (status, next_retry_at, processing_lease_until);
CREATE INDEX idx_upload_batch_entry_sample_assay
    ON upload_batch_entry (sample_id, assay_id);

CREATE TABLE upload_batch_staging_budget (
    id smallint NOT NULL PRIMARY KEY CHECK (id = 1),
    reserved_bytes bigint NOT NULL DEFAULT 0 CHECK (reserved_bytes >= 0)
);
INSERT INTO upload_batch_staging_budget (id, reserved_bytes) VALUES (1, 0);

ALTER TABLE file ALTER COLUMN object_key TYPE varchar(1024);
ALTER TABLE file ADD COLUMN size_bytes bigint CHECK (size_bytes IS NULL OR size_bytes >= 0);
ALTER TABLE file ADD COLUMN sha256 varchar(64)
    CHECK (sha256 IS NULL OR sha256 ~ '^[0-9A-Fa-f]{64}$');
ALTER TABLE file ADD COLUMN upload_batch_entry_id uuid;
ALTER TABLE file ADD CONSTRAINT fk_file_upload_batch_entry
    FOREIGN KEY (upload_batch_entry_id) REFERENCES upload_batch_entry(id) ON DELETE SET NULL;
ALTER TABLE file ADD CONSTRAINT uq_file_upload_batch_entry UNIQUE (upload_batch_entry_id);
CREATE INDEX idx_file_upload_batch_entry ON file (upload_batch_entry_id);