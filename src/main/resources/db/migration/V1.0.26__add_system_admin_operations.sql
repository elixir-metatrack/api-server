ALTER TABLE project_invitation
    ADD COLUMN inviter_system_admin boolean NOT NULL DEFAULT false;

CREATE SEQUENCE system_admin_audit_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE system_admin_audit
(
    id              bigint                   NOT NULL PRIMARY KEY,
    actor_id        uuid                     NOT NULL,
    action          varchar(16)              NOT NULL,
    target          varchar(512)             NOT NULL,
    response_status integer                  NOT NULL,
    occurred_at     timestamp with time zone NOT NULL
);

CREATE INDEX idx_system_admin_audit_occurred_at ON system_admin_audit (occurred_at);