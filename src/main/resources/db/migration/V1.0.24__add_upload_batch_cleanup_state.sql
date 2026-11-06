ALTER TABLE upload_batch ADD COLUMN sftp_user_revoked_at timestamp with time zone;
ALTER TABLE upload_batch ADD COLUMN sftp_user_revocation_attempts integer NOT NULL DEFAULT 0
    CHECK (sftp_user_revocation_attempts >= 0);
ALTER TABLE upload_batch ADD COLUMN sftp_user_revocation_next_attempt_at timestamp with time zone;
ALTER TABLE upload_batch ADD COLUMN sftp_user_revocation_last_error text;
CREATE INDEX idx_upload_batch_sftp_revocation
    ON upload_batch (status, sftp_user_revoked_at, sftp_user_revocation_next_attempt_at);

ALTER TABLE upload_batch_entry ADD COLUMN staging_cleanup_after timestamp with time zone;
ALTER TABLE upload_batch_entry ADD COLUMN staging_cleanup_completed_at timestamp with time zone;
ALTER TABLE upload_batch_entry ADD COLUMN previous_object_cleanup_after timestamp with time zone;
ALTER TABLE upload_batch_entry ADD COLUMN previous_object_cleanup_completed_at timestamp with time zone;
CREATE INDEX idx_upload_batch_entry_staging_cleanup
    ON upload_batch_entry (status, staging_cleanup_after, staging_cleanup_completed_at);