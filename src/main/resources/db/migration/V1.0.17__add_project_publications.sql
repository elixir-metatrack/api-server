CREATE SEQUENCE project_publication_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE project_publication (
    id bigint NOT NULL PRIMARY KEY,
    project_id bigint NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    title varchar(255) NOT NULL,
    doi varchar(255)
);

CREATE INDEX idx_project_publication_project ON project_publication(project_id);

CREATE TABLE project_publication_link (
    publication_id bigint NOT NULL REFERENCES project_publication(id) ON DELETE CASCADE,
    list_order integer NOT NULL,
    link text NOT NULL,
    PRIMARY KEY (publication_id, list_order)
);

CREATE TABLE project_publication_pid (
    publication_id bigint NOT NULL REFERENCES project_publication(id) ON DELETE CASCADE,
    list_order integer NOT NULL,
    pid text NOT NULL,
    PRIMARY KEY (publication_id, list_order)
);