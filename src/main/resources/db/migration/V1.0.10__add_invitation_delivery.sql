ALTER TABLE project_invitation
    ADD COLUMN delivery_status varchar(32) NOT NULL DEFAULT 'NOT_ATTEMPTED',
    ADD COLUMN delivery_attempt_id uuid,
    ADD COLUMN delivery_attempted_on timestamptz,
    ADD COLUMN delivery_completed_on timestamptz,
    ADD COLUMN delivery_retry_after timestamptz,
    ADD CONSTRAINT invitation_delivery_status CHECK (delivery_status IN ('NOT_ATTEMPTED', 'SENDING', 'SENT', 'FAILED', 'UNKNOWN')),
    ADD CONSTRAINT invitation_delivery_attempt CHECK (
        (delivery_status = 'NOT_ATTEMPTED' AND delivery_attempt_id IS NULL AND delivery_attempted_on IS NULL
            AND delivery_completed_on IS NULL AND delivery_retry_after IS NULL)
        OR (delivery_status <> 'NOT_ATTEMPTED' AND delivery_attempt_id IS NOT NULL AND delivery_attempted_on IS NOT NULL
            AND delivery_retry_after IS NOT NULL AND delivery_retry_after >= delivery_attempted_on
            AND ((delivery_status = 'SENDING' AND delivery_completed_on IS NULL)
                OR (delivery_status IN ('SENT', 'FAILED', 'UNKNOWN') AND delivery_completed_on IS NOT NULL
                    AND delivery_completed_on >= delivery_attempted_on))));