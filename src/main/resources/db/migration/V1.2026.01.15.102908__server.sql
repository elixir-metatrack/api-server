
    create table assay (
        id uuid not null,
        created_on timestamp(6) with time zone,
        insert_size integer,
        instrument_model varchar(255),
        library_layout varchar(255),
        library_name varchar(255),
        library_selection varchar(255),
        library_source varchar(255),
        modified_on timestamp(6) with time zone,
        name varchar(255) not null,
        study_accession varchar(255),
        project_id bigint,
        primary key (id)
    );

    create table assay_sample (
        assay_id uuid not null,
        sample_id uuid not null,
        primary key (assay_id, sample_id)
    );

    alter table if exists assay 
       drop constraint if exists UKidwpse6bspej1ldj8jns8ofc2;

    alter table if exists assay 
       add constraint UKidwpse6bspej1ldj8jns8ofc2 unique (name, project_id);

    alter table if exists project_member 
       drop constraint if exists UKk6l7cvhbqw8wpe2kcufygwwac;

    alter table if exists project_member 
       add constraint UKk6l7cvhbqw8wpe2kcufygwwac unique (member_id, project_id);

    alter table if exists sample 
       drop constraint if exists UK9j5ajh6p73wyoe2dx3j9aa4r2;

    alter table if exists sample 
       add constraint UK9j5ajh6p73wyoe2dx3j9aa4r2 unique (name, project_id);

    alter table if exists assay 
       add constraint FKp7ncf7gc9fp5klgxbii1ek36o 
       foreign key (project_id) 
       references project;

    alter table if exists assay_sample 
       add constraint FKisdquty39th2mhjbdp067dq2f 
       foreign key (sample_id) 
       references sample;

    alter table if exists assay_sample 
       add constraint FK1iu7d9n26nar69qoe9uplmnn2 
       foreign key (assay_id) 
       references assay;
