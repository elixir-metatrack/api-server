CREATE TABLE sample_vocabulary (
    id uuid NOT NULL,
    project_id bigint NOT NULL,
    field_key varchar(64) NOT NULL,
    created_on timestamp(6) with time zone NOT NULL,
    modified_on timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (project_id, field_key),
    CONSTRAINT fk_sample_vocabulary_project FOREIGN KEY (project_id) REFERENCES project ON DELETE CASCADE
);

CREATE TABLE sample_vocabulary_term (
    id uuid NOT NULL,
    vocabulary_id uuid NOT NULL,
    value text NOT NULL CHECK (btrim(value) <> ''),
    PRIMARY KEY (id),
    UNIQUE (vocabulary_id, value),
    CONSTRAINT fk_sample_vocabulary_term_vocabulary FOREIGN KEY (vocabulary_id)
        REFERENCES sample_vocabulary ON DELETE CASCADE
);

CREATE INDEX idx_sample_vocabulary_project ON sample_vocabulary (project_id);