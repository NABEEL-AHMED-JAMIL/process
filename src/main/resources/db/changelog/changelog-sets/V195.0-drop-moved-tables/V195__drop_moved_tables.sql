-- Owner decision 2026-10-01: drop the leftover tables in etl_job that are empty and that nothing reads or writes any more.
--
--   ai_agent                         -- moved to ai_db (MIG-147/150, V63); read-only copy here since then
--   analytics_benchmark_result       -- moved to analytics_db (MIG-128, V62); read-only copy here since then
--   analytics_dataset                -- moved to analytics_db (MIG-128, V62); read-only copy here since then
--   payment                          -- moved to billing_db (MIG-88/89, V61); read-only copy here since then
--   timestamptz_v100_unrepresentable -- V100's store for DST-gap values, kept only for V100's rollback; it never held
--                                       a row on the live instance, and the timestamptz report is no longer needed
--
-- With them go the sequences named after them (ai_agent_seq, analytics_benchmark_result_seq, analytics_dataset_seq,
-- from the V50 baseline and owned by nothing) and payment's own payment_payment_id_seq (owned by its column).
--
-- Only an EMPTY table is dropped. The other read-only copies (ai_prompt*, ai_model_connection, analytics_query*,
-- analytics_analysis, analytics_dashboard*, billing_account, billing_document, billing_number_counter, invoice_line,
-- notification_moved_mig21, document_converter_task_moved_mig41) still hold rows and are not touched here; the owner
-- decides those separately. The same rule holds on every instance this runs on: a table here that holds rows stays,
-- with its sequence, and the run says so (NOTICE) instead of failing. row_security is off for the check, so a policy
-- can never make a table with rows look empty -- a role subject to one gets an error, not a silent drop.
--
-- If timestamptz_v100_unrepresentable holds gap values (a database that had any when V100 ran), it stays, and V100's
-- rollback restores them exactly as before. V100's rollback already copes with the table being absent.
--
-- Rollback: V195__rollback.sql recreates every table that is missing, empty, with its columns, keys, indexes,
-- comments, read-only trigger, row security policy and grants as they stood on etl_job on 2026-09-30.

DO $$
DECLARE
    t text;
    has_rows boolean;
    seq text;
BEGIN
    PERFORM set_config('row_security', 'off', true);
    FOREACH t IN ARRAY ARRAY['ai_agent', 'analytics_benchmark_result', 'analytics_dataset', 'payment',
                             'timestamptz_v100_unrepresentable'] LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            CONTINUE;
        END IF;
        EXECUTE format('SELECT EXISTS (SELECT 1 FROM public.%I)', t) INTO has_rows;
        IF has_rows THEN
            RAISE NOTICE 'V195: public.% holds rows; left in place', t;
            CONTINUE;
        END IF;
        EXECUTE format('DROP TABLE public.%I', t);
        seq := CASE t WHEN 'payment' THEN NULL WHEN 'timestamptz_v100_unrepresentable' THEN NULL ELSE t || '_seq' END;
        IF seq IS NOT NULL THEN
            EXECUTE format('DROP SEQUENCE IF EXISTS public.%I', seq);
        END IF;
    END LOOP;
END
$$;
