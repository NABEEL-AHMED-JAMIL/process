-- The constraints only; the respelt values stay respelt (they were unreadable before).
DO $$
DECLARE
    c record;
BEGIN
    FOR c IN SELECT conrelid::regclass AS tbl, conname FROM pg_constraint
             WHERE contype = 'c' AND conname LIKE 'ck\_%\_enum' AND connamespace = current_schema()::regnamespace LOOP
        EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', c.tbl, c.conname);
    END LOOP;
END
$$;
