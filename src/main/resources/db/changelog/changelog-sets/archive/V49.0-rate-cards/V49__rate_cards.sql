-- Rate cards: an invoice names the card that priced it, and a frozen line keeps what the
-- calculation applied (allowance, graduated tiers), so a bill reads the same after the card changes.
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS rate_card_name VARCHAR(120);
ALTER TABLE invoice_line ADD COLUMN IF NOT EXISTS included_quantity NUMERIC(20, 5);
ALTER TABLE invoice_line ADD COLUMN IF NOT EXISTS billable_quantity NUMERIC(20, 5);
ALTER TABLE invoice_line ADD COLUMN IF NOT EXISTS pricing_detail TEXT;
