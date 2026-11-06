CREATE TABLE upload_batch_transfer_admission (
    id uuid NOT NULL PRIMARY KEY,
    entry_id uuid NOT NULL UNIQUE REFERENCES upload_batch_entry(id) ON DELETE CASCADE,
    admitted_at timestamp with time zone NOT NULL
);

CREATE INDEX idx_upload_batch_transfer_admission_admitted_at
    ON upload_batch_transfer_admission (admitted_at);