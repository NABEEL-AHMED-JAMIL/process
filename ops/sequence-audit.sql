-- MIG-177 (P6): run against a database before it takes writes after a table move, and after any
-- copy. Prints BEHIND for every column-owned sequence whose next value would collide with an
-- existing key; silence means every owned sequence is ahead of its table. Entity sequences that
-- no column owns (Hibernate @GenericGenerator) are checked by SchemaProvenancePostgresTest and by
-- comparing last_value with max(id) per entity.
--   docker exec -i postgres_db psql -U <user> -d <database> -q < ops/sequence-audit.sql
DO $$
DECLARE r record; mx bigint; lv bigint; called boolean;
BEGIN
  FOR r IN
    SELECT n.nspname AS sch, c.relname AS tbl, a.attname AS col, pg_get_serial_sequence(format('%I.%I', n.nspname, c.relname), a.attname) AS seq
    FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
    WHERE c.relkind='r' AND n.nspname NOT IN ('pg_catalog','information_schema')
      AND pg_get_serial_sequence(format('%I.%I', n.nspname, c.relname), a.attname) IS NOT NULL
  LOOP
    EXECUTE format('SELECT max(%I) FROM %I.%I', r.col, r.sch, r.tbl) INTO mx;
    EXECUTE format('SELECT last_value, is_called FROM %s', r.seq) INTO lv, called;
    IF mx IS NOT NULL AND (lv < mx OR (lv = mx AND NOT called)) THEN
      RAISE NOTICE 'BEHIND %.%.% seq=% last=% max=%', r.sch, r.tbl, r.col, r.seq, lv, mx;
    END IF;
  END LOOP;
END $$;
