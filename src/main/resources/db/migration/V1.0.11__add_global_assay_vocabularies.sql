CREATE TABLE global_assay_vocabulary (
    id uuid NOT NULL,
    field_key varchar(64) NOT NULL,
    created_on timestamp(6) with time zone NOT NULL,
    modified_on timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (field_key)
);

CREATE TABLE global_assay_vocabulary_term (
    id uuid NOT NULL,
    vocabulary_id uuid NOT NULL,
    value text NOT NULL CHECK (btrim(value) <> ''),
    PRIMARY KEY (id),
    UNIQUE (vocabulary_id, value),
    CONSTRAINT fk_global_assay_vocabulary_term_vocabulary FOREIGN KEY (vocabulary_id)
        REFERENCES global_assay_vocabulary ON DELETE CASCADE
);