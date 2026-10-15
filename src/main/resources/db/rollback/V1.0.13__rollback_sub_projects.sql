-- Destructive MANUAL rollback of V1.0.13__add_sub_projects.sql, not Flyway undo.
-- Follow README prerequisites; run with psql -X -v ON_ERROR_STOP=1 -f this-file.
-- History is deliberately unchanged until the separate verified reconciliation.
begin;
set local lock_timeout = '10s';
lock table flyway_schema_history, project, project_member, join_project,
    sample, assay, project_sample, sample_metadata_field, sample_vocabulary in access exclusive mode;

do $$
begin
    if not exists (select 1 from flyway_schema_history
        where version = '1.0.13' and script = 'V1.0.13__add_sub_projects.sql'
        and type = 'SQL' and success
        and installed_rank = (select max(installed_rank) from flyway_schema_history)) then
        raise exception 'Expected sub-project migration as latest successful migration; undo later migrations first';
    end if;
    if exists (select 1 from sample s join project p on p.id = s.project_id where p.parent_project_id is not null)
        or exists (select 1 from assay a join project p on p.id = a.project_id where p.parent_project_id is not null)
        or exists (select 1 from sample_metadata_field f join project p on p.id = f.project_id where p.parent_project_id is not null)
        or exists (select 1 from sample_vocabulary v join project p on p.id = v.project_id where p.parent_project_id is not null) then
        raise exception 'Unexpected sub-project-owned data; resolve ownership before rollback';
    end if;
end $$;

-- Remove visibility links and non-cascading references before their projects.
drop table project_sample;
delete from project_member where project_id in (select id from project where parent_project_id is not null);
delete from join_project where project_id in (select id from project where parent_project_id is not null);
-- Project deletion cascades to invitations -> notifications. The ownership guard
-- protects metadata fields/values and vocabularies/terms, even unused definitions.
-- Root-owned samples, assays and files stay.
delete from project where parent_project_id is not null;
alter table project drop constraint fk_project_parent_project;
alter table project drop column parent_project_id;
commit;
