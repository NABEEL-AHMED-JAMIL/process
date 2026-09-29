-- V190's way back: retention_log and file_access_log with their sequences (their policies go with them). Nothing else
-- was touched; what the sweep removed stays removed, and the downloads happened -- only their record is gone.
DROP TABLE IF EXISTS public.file_access_log;
DROP SEQUENCE IF EXISTS public.file_access_log_seq;
DROP TABLE IF EXISTS public.retention_log;
DROP SEQUENCE IF EXISTS public.retention_log_seq;
