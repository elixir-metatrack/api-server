ALTER TABLE upload_batch_entry ADD COLUMN multipart_upload_id text;
ALTER TABLE upload_batch_entry ADD COLUMN multipart_part_size_bytes bigint
    CHECK (multipart_part_size_bytes IS NULL OR multipart_part_size_bytes > 0);
ALTER TABLE upload_batch_entry ADD COLUMN destination_etag text;
ALTER TABLE upload_batch_entry ADD COLUMN destination_version_id text;