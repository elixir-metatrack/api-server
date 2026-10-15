alter table project add column parent_project_id bigint;

alter table project
    add constraint fk_project_parent_project
    foreign key (parent_project_id)
    references project;

create index idx_project_parent_project_id on project (parent_project_id);

create table project_sample (
    project_id bigint not null,
    sample_id uuid not null,
    primary key (project_id, sample_id)
);

-- Cascade: a sample no longer being included in / a sub-project being deleted
-- should just drop the visibility link, not block the delete.
alter table project_sample
    add constraint fk_project_sample_project
    foreign key (project_id)
    references project
    on delete cascade;

alter table project_sample
    add constraint fk_project_sample_sample
    foreign key (sample_id)
    references sample
    on delete cascade;
