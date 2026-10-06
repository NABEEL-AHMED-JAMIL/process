-- MIG-332's way back: the customer API's four tables (their policies go with them) and sequences. Runs already started
-- keep running; only the register of how they were started goes.
DROP TABLE IF EXISTS public.api_event;
DROP SEQUENCE IF EXISTS public.api_event_seq;
DROP TABLE IF EXISTS public.event_route;
DROP SEQUENCE IF EXISTS public.event_route_seq;
DROP TABLE IF EXISTS public.api_intake;
DROP SEQUENCE IF EXISTS public.api_intake_seq;
DROP TABLE IF EXISTS public.api_idempotency_receipt;
