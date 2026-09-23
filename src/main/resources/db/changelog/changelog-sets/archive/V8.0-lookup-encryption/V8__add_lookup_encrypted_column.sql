-- Add is_encrypted column to lookup_data
-- Marks whether lookup_value holds ciphertext (AES-256-GCM, see process.util.EncryptionUtil)
-- instead of plain text. Default/backfilled to FALSE so existing rows keep working unchanged.
--
-- Guarded on the table existing: on a brand-new database this migration runs before Hibernate
-- (ddl-auto=update) has ever created lookup_data, so without the guard this whole changeset
-- fails outright. Hibernate's LookupData#encrypted mapping already declares this column
-- NOT NULL when it creates the table fresh, so skipping here is a safe no-op in that case --
-- this migration only has real work to do against a pre-existing table from before this
-- column was added to the entity. Plain "IF EXISTS" only exists for ALTER TABLE, not UPDATE,
-- so the whole thing is wrapped in a DO block to guard all three statements consistently.

DO $$
BEGIN
    IF to_regclass('public.lookup_data') IS NOT NULL THEN
        ALTER TABLE lookup_data ADD COLUMN IF NOT EXISTS is_encrypted BOOLEAN DEFAULT FALSE;
        UPDATE lookup_data SET is_encrypted = FALSE WHERE is_encrypted IS NULL;
        ALTER TABLE lookup_data ALTER COLUMN is_encrypted SET NOT NULL;
    END IF;
END $$;
