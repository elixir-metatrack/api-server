CREATE TABLE global_sample_vocabulary (
    id uuid NOT NULL,
    field_key varchar(64) NOT NULL,
    created_on timestamp(6) with time zone NOT NULL,
    modified_on timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (field_key)
);

CREATE TABLE global_sample_vocabulary_term (
    id uuid NOT NULL,
    vocabulary_id uuid NOT NULL,
    value text NOT NULL CHECK (btrim(value) <> ''),
    PRIMARY KEY (id),
    UNIQUE (vocabulary_id, value),
    CONSTRAINT fk_global_sample_vocabulary_term_vocabulary FOREIGN KEY (vocabulary_id)
        REFERENCES global_sample_vocabulary ON DELETE CASCADE
);

DELETE FROM sample_vocabulary
WHERE field_key IN (
    'alias',
    'mlst',
    'project_title',
    'description',
    'isolate',
    'collected_by',
    'environmental_sample',
    'host_associated',
    'host_common_name',
    'host_subject_id',
    'collector_name',
    'collecting_institution',
    'host_sex',
    'influenza_test_method',
    'influenza_test_result',
    'other_pathogens_tested',
    'other_pathogens_test_result',
    'host_habitat',
    'isolation_source_host_associated',
    'host_behaviour',
    'isolation_source_non_host_associated',
    'influenza_virus_type',
    'influenza_sub_type',
    'serovar',
    'strain',
    'host_age',
    'county',
    'commune',
    'hospital_health_institution',
    'isolation_source',
    'location',
    'sequencing_lab',
    'institution',
    'host_health_state'
);