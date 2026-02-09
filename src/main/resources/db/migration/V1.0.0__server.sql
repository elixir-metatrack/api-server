
    create sequence file_seq start with 1 increment by 50;

    create sequence join_project_seq start with 1 increment by 50;

    create sequence project_member_seq start with 1 increment by 50;

    create sequence project_seq start with 1 increment by 50;

    create table assay (
        id uuid not null,
        created_on timestamp(6) with time zone,
        insert_size integer,
        instrument_model varchar(255),
        library_layout varchar(255),
        library_name varchar(255),
        library_selection varchar(255),
        library_source varchar(255),
        library_strategy varchar(255),
        modified_on timestamp(6) with time zone,
        name varchar(255) not null,
        study_accession varchar(255),
        project_id bigint,
        primary key (id),
        unique (name, project_id)
    );

    create table assay_sample (
        assay_id uuid not null,
        sample_id uuid not null,
        primary key (assay_id, sample_id)
    );

    create table file (
        id bigint not null,
        file_name varchar(255) not null,
        object_key varchar(255) not null unique,
        status varchar(255) check ((status in ('PENDING','UPLOADED'))),
        uuid uuid not null unique,
        virtual_path varchar(255) not null unique,
        sample_id uuid,
        primary key (id)
    );

    create table join_project (
        id bigint not null,
        project_id bigint not null,
        role varchar(255) check ((role in ('OWNER','ADMIN','EDITOR','VIEWER'))),
        user_id uuid,
        primary key (id)
    );

    create table project (
        id bigint not null,
        created_on timestamp(6) with time zone,
        description varchar(255),
        modified_on timestamp(6) with time zone,
        name varchar(255) not null unique,
        owner uuid not null,
        primary key (id)
    );

    create table project_member (
        id bigint not null,
        member_id uuid not null,
        role varchar(255) not null check ((role in ('OWNER','ADMIN','EDITOR','VIEWER'))),
        project_id bigint not null,
        primary key (id),
        unique (member_id, project_id)
    );

    create table sample (
        id uuid not null,
        alias varchar(255),
        collection_date date,
        created_on timestamp(6) with time zone,
        host_health_state varchar(255),
        host_tax_id integer,
        institution varchar(255),
        isolation_source varchar(255),
        location varchar(255),
        mlst varchar(255),
        modified_on timestamp(6) with time zone,
        name varchar(255),
        sequencing_lab varchar(255),
        tax_id integer,
        project_id bigint,
        primary key (id),
        unique (name, project_id)
    );

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

    alter table if exists file 
       add constraint FKe38dhse49y7t0xdvrbwo2rh5d 
       foreign key (sample_id) 
       references sample;

    alter table if exists project_member 
       add constraint FK103dwxad12nbaxtmnwus4eft2 
       foreign key (project_id) 
       references project;

    alter table if exists sample 
       add constraint FKeytsctv5b8stbijub6yeil5ie 
       foreign key (project_id) 
       references project;
