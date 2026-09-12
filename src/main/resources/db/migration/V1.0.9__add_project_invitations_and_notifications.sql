CREATE TABLE project_invitation (
    id uuid NOT NULL PRIMARY KEY,
    project_id bigint NOT NULL REFERENCES project ON DELETE CASCADE,
    recipient_email varchar(254) NOT NULL
        CHECK (recipient_email = lower(btrim(recipient_email)) AND recipient_email ~ '^[^[:space:]@]+@[^[:space:]@]+$'),
    recipient_id uuid,
    inviter_id uuid NOT NULL,
    inviter_display_name varchar(255) NOT NULL,
    role varchar(255) NOT NULL CHECK (role IN ('VIEWER', 'EDITOR', 'ADMIN')),
    status varchar(255) NOT NULL CHECK (status IN ('PENDING', 'ACCEPTED', 'DECLINED', 'REVOKED', 'EXPIRED')),
    created_on timestamp(6) with time zone NOT NULL,
    expires_on timestamp(6) with time zone NOT NULL,
    responded_on timestamp(6) with time zone,
    version bigint NOT NULL DEFAULT 0,
    CHECK (expires_on > created_on),
    CHECK ((status = 'PENDING' AND responded_on IS NULL) OR (status <> 'PENDING' AND responded_on IS NOT NULL)),
    CHECK (responded_on IS NULL OR responded_on >= created_on),
    CHECK (status <> 'EXPIRED' OR responded_on >= expires_on),
    CHECK (status NOT IN ('ACCEPTED', 'DECLINED', 'REVOKED') OR responded_on < expires_on),
    CHECK (status NOT IN ('ACCEPTED', 'DECLINED') OR recipient_id IS NOT NULL)
);

CREATE UNIQUE INDEX uq_project_invitation_pending_email
    ON project_invitation (project_id, recipient_email) WHERE status = 'PENDING';
CREATE INDEX idx_project_invitation_project ON project_invitation (project_id, created_on DESC, id);
CREATE INDEX idx_project_invitation_pending_email ON project_invitation (recipient_email, expires_on) WHERE status = 'PENDING';
CREATE INDEX idx_project_invitation_recipient ON project_invitation (recipient_id, created_on DESC, id);

CREATE TABLE notification (
    id uuid NOT NULL PRIMARY KEY,
    recipient_id uuid NOT NULL,
    type varchar(255) NOT NULL CHECK (type IN ('PROJECT_INVITATION')),
    created_on timestamp(6) with time zone NOT NULL,
    read_on timestamp(6) with time zone,
    title varchar(255) NOT NULL,
    content text NOT NULL,
    invitation_id uuid REFERENCES project_invitation ON DELETE CASCADE,
    UNIQUE (recipient_id, invitation_id),
    CHECK (type <> 'PROJECT_INVITATION' OR invitation_id IS NOT NULL)
);

CREATE INDEX idx_notification_recipient ON notification (recipient_id, created_on DESC, id);
CREATE INDEX idx_notification_unread ON notification (recipient_id, created_on DESC, id) WHERE read_on IS NULL;
CREATE INDEX idx_notification_invitation ON notification (invitation_id);