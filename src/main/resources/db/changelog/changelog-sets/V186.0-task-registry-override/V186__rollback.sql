-- V186's way back: task_registry_override with its sequence (its policy goes with it). Nothing else was touched; every
-- task is at its default again.
DROP TABLE IF EXISTS public.task_registry_override;
DROP SEQUENCE IF EXISTS public.task_registry_override_seq;
