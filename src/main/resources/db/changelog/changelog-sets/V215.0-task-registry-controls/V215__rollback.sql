-- V215's way back: the block and audit tables (their policies go with them) and the switch's role column. The switches
-- themselves (task_registry_override) stay; the old code reads them as before and knows nothing of blocks.
DROP TABLE IF EXISTS public.task_registry_audit;
DROP SEQUENCE IF EXISTS public.task_registry_audit_seq;
DROP TABLE IF EXISTS public.task_registry_block;
ALTER TABLE public.task_registry_override DROP COLUMN IF EXISTS updated_by_role;
