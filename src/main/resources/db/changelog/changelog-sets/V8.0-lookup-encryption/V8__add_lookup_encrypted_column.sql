-- Add is_encrypted column to lookup_data
-- Marks whether lookup_value holds ciphertext (AES-256-GCM, see process.util.EncryptionUtil)
-- instead of plain text. Default/backfilled to FALSE so existing rows keep working unchanged.

ALTER TABLE lookup_data ADD COLUMN IF NOT EXISTS is_encrypted BOOLEAN DEFAULT FALSE;
-- Ensure no nulls
UPDATE lookup_data SET is_encrypted = FALSE WHERE is_encrypted IS NULL;
ALTER TABLE lookup_data ALTER COLUMN is_encrypted SET NOT NULL;
