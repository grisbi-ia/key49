-- Key49 V014: Agregar estado pending_approval al CHECK constraint de tenants
-- Un tenant en pending_approval ya verificó su email pero espera aprobación del admin.

ALTER TABLE public.tenants DROP CONSTRAINT IF EXISTS chk_tenants_status;

ALTER TABLE public.tenants
    ADD CONSTRAINT chk_tenants_status
    CHECK (status IN ('active', 'suspended', 'pending', 'pending_approval', 'failed'));
