-- Manual rollback for V1.0.9__add_sub_projects.sql
--
-- Flyway Community Edition does not support automatic "undo" migrations
-- (that's a Flyway Teams feature). This script is NOT picked up by Flyway
-- automatically - it lives outside src/main/resources/db/migration on purpose.
--
-- Use only if the sub-projects feature needs to be reverted after it was
-- already applied to a database. This is destructive: any existing
-- sub-projects and their sample links are permanently deleted.
--
-- How to run:
--   docker exec -i pg-metatrack psql -U metatrack -d metatrack \
--     < src/main/resources/db/rollback/V1.0.9__rollback_sub_projects.sql
--
-- After running this, also remove the corresponding row from
-- flyway_schema_history so Flyway doesn't think V1.0.9 is still applied:
--   DELETE FROM flyway_schema_history WHERE version = '1.0.9';

-- Delete any sub-projects first (their samples/assays stay - they belong to
-- the root project already, this only removes the sub-project rows).
-- project_member and join_project have no cascading FK to project, so their
-- rows for sub-projects must be cleared explicitly before the project rows.
delete from project_member where project_id in (select id from project where parent_project_id is not null);
delete from join_project where project_id in (select id from project where parent_project_id is not null);
delete from project where parent_project_id is not null;

drop table if exists project_sample;

alter table project drop constraint if exists fk_project_parent_project;

alter table project drop column if exists parent_project_id;
