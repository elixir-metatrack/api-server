
    create table join_project (
        id bigint not null,
        project_id bigint not null,
        role varchar(255) check ((role in ('OWNER','ADMIN','EDITOR','VIEWER'))),
        user_id uuid,
        primary key (id)
    );

    create sequence join_project_seq start with 1 increment by 50;
