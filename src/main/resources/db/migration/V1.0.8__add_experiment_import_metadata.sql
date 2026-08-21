alter table assay add column if not exists sequencing_platform varchar(255);
alter table assay add column if not exists sequencing_laboratory varchar(255);
alter table file add column if not exists md5 varchar(255);
alter table file add column if not exists unencrypted_md5 varchar(255);