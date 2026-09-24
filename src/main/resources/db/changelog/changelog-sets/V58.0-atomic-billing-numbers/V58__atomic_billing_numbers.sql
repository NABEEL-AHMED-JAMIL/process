-- MIG-9: billing numbers come from one counter row per series, taken and advanced under that row's
-- lock in the same transaction as the document it numbers. Concurrent issuers, on one instance or
-- several, queue on the row, and a transaction that rolls back returns its number, so a month's
-- invoices, credit notes and receipts stay gapless (INV-2026-09-0001, -0002, ...).
-- No semicolons in these comments: the changeset splits statements on them.
-- base is what every number of a series shares: INV-2026-09, RCP-2026-09, STM-2905-2026-01-01-2026-12-31.
CREATE TABLE IF NOT EXISTS billing_number_counter (
    base       VARCHAR(64) PRIMARY KEY,
    last_value INTEGER     NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Every customer-facing document has its own number (payment slips have none).
CREATE UNIQUE INDEX IF NOT EXISTS ux_billing_document_number ON billing_document (number) WHERE number IS NOT NULL;
