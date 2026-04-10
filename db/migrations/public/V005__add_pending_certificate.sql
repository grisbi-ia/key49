-- V005: Add pending certificate columns for zero-downtime rotation
-- During rotation, the new certificate is stored in pending columns
-- until explicitly activated. In-flight documents continue using the active certificate.

ALTER TABLE tenants ADD COLUMN pending_certificate_p12          BYTEA;
ALTER TABLE tenants ADD COLUMN pending_certificate_password_enc BYTEA;
ALTER TABLE tenants ADD COLUMN pending_certificate_subject      VARCHAR(500);
ALTER TABLE tenants ADD COLUMN pending_certificate_expiration   TIMESTAMP WITH TIME ZONE;
ALTER TABLE tenants ADD COLUMN pending_certificate_serial       VARCHAR(100);
