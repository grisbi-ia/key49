-- V013: Add notify_final_consumer flag to tenants
-- Controls whether email notifications are sent when the document recipient
-- is "Consumidor Final" (all-nines identification: 9999999999 or 9999999999999).
-- Default true preserves existing behaviour for all current tenants.

ALTER TABLE tenants
    ADD COLUMN notify_final_consumer BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN tenants.notify_final_consumer IS
    'If false, email notifications are skipped when the recipient ID is Consumidor Final (9999999999 or 9999999999999)';
