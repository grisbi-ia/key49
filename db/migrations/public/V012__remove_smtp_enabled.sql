-- V012: Remove smtp_enabled column from tenants
-- This column was redundant: smtp is considered configured (and active)
-- when smtp_host is present. The email_provider column ('smtp' | 'plunk')
-- controls routing. No explicit on/off toggle is needed.

ALTER TABLE tenants DROP COLUMN smtp_enabled;
