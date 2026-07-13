CREATE TABLE sample_metadata_field (
    id uuid NOT NULL,
    project_id bigint NOT NULL,
    field_key varchar(64) NOT NULL,
    label varchar(255) NOT NULL,
    type varchar(32) NOT NULL CHECK (type IN ('TEXT', 'NUMBER', 'BOOLEAN', 'DATE')),
    created_on timestamp(6) with time zone NOT NULL,
    modified_on timestamp(6) with time zone NOT NULL,
    archived_on timestamp(6) with time zone,
    PRIMARY KEY (id),
    UNIQUE (project_id, field_key),
    CONSTRAINT fk_sample_metadata_field_project FOREIGN KEY (project_id) REFERENCES project ON DELETE CASCADE
);

CREATE INDEX idx_sample_metadata_field_project_active
    ON sample_metadata_field (project_id, archived_on);

CREATE TABLE sample_metadata_value (
    id uuid NOT NULL,
    sample_id uuid NOT NULL,
    field_id uuid NOT NULL,
    text_value text,
    number_value numeric,
    boolean_value boolean,
    date_value date,
    PRIMARY KEY (id),
    UNIQUE (sample_id, field_id),
    CONSTRAINT fk_sample_metadata_value_sample FOREIGN KEY (sample_id) REFERENCES sample ON DELETE CASCADE,
    CONSTRAINT fk_sample_metadata_value_field FOREIGN KEY (field_id) REFERENCES sample_metadata_field ON DELETE CASCADE,
    CONSTRAINT chk_sample_metadata_single_value CHECK (
        num_nonnulls(text_value, number_value, boolean_value, date_value) = 1
    )
);

CREATE INDEX idx_sample_metadata_value_field ON sample_metadata_value (field_id);