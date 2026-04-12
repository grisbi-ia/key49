-- V009: Add email_notifications_enabled flag to tenants
-- Allows tenants to opt out of email notifications sent by Key49.
-- When false, the notify pipeline skips email sending and the document
-- remains in NOTIFIED state with emailStatus = 'SKIPPED'.

ALTER TABLE tenants
    ADD COLUMN email_notifications_enabled BOOLEAN NOT NULL DEFAULT true;

COMMENT ON COLUMN tenants.email_notifications_enabled
    IS 'Whether Key49 sends email notifications for this tenant. If false, emails are skipped (emailStatus=SKIPPED).';
