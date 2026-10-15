-- MANUAL only, after successful rollback and verification described in README.
begin;
set local lock_timeout = '10s';
lock table flyway_schema_history, project, file in access exclusive mode;
do $$
declare
    removed integer;
begin
    if to_regclass('project_sample') is not null
        or exists (select 1 from information_schema.columns where table_schema = current_schema()
            and table_name = 'project' and column_name = 'parent_project_id') then
        raise exception 'Sub-project schema still exists; rollback has not completed';
    end if;
    if not exists (select 1 from information_schema.columns where table_schema = current_schema()
            and table_name = 'file' and column_name = 'read_role')
        or not exists (select 1 from flyway_schema_history where version = '1.0.12'
            and script = 'V1.0.12__add_file_read_role.sql' and success) then
        raise exception 'Main read-role migration must remain intact';
    end if;
    delete from flyway_schema_history where version = '1.0.13'
        and script = 'V1.0.13__add_sub_projects.sql' and type = 'SQL' and success
        and installed_rank = (select max(installed_rank) from flyway_schema_history);
    get diagnostics removed = row_count;
    if removed <> 1 then
        raise exception 'Expected exactly one latest sub-project history entry';
    end if;
end $$;
commit;