ALTER TABLE project
    ADD COLUMN data_owning_organization varchar(255),
    ADD COLUMN detailed_description text,
    ADD COLUMN comments text;