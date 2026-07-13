alter table file add column assay_id uuid;

create index idx_file_assay_id on file (assay_id);
create index idx_file_sample_assay on file (sample_id, assay_id);

alter table file
    add constraint fk_file_assay
    foreign key (assay_id)
    references assay
    on delete cascade;

alter table file
    add constraint fk_file_assay_sample
    foreign key (assay_id, sample_id)
    references assay_sample (assay_id, sample_id)
    on delete cascade;