-- One column, holding E.164: a leading + then country code then national number, digits only
-- (+923001234567). Storing the country separately would let the two drift apart, and E.164
-- already carries it -- the dialling code is a prefix, so the country can always be recovered.
--
-- Nullable: a phone number is not something an account needs to exist, and every user created
-- before today genuinely has none.
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS phone_number VARCHAR(20);

COMMENT ON COLUMN app_user.phone_number IS
  'Phone in E.164: +<country code><national number>, digits only, 20 chars is the format maximum.';
