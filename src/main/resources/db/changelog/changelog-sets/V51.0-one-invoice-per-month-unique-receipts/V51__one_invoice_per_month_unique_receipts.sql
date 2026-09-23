-- One live invoice per workspace per month, and no two receipts sharing a number.
--
-- Both were rules the code meant to keep and did not. draft() promised to leave an issued month
-- alone but looked only for a draft to rebuild, so once a month was issued each "Draft" made
-- another; dev reached nine live invoices for one workspace's September. Receipt numbers were
-- every payment row counted plus one -- submitted and rejected slips included -- so two slips
-- submitted before either was verified came out with the same number; dev carried two such
-- pairs, from ordinary use, on documents a customer files for tax.
--
-- The service now refuses both. These indexes are the same two rules held where no code path
-- can go around them: a second route to an invoice, a retry, or two verifications racing each
-- other now fails loudly on the constraint instead of leaving a duplicate that nobody sees.
--
-- A voided invoice is outside the first rule on purpose -- voiding is how a month is billed
-- again. Credit notes are outside it too: kind = 'invoice' only, since one month can carry more
-- than one credit.
--
-- The changeset's precondition refuses to run where existing rows already break either rule,
-- and names the query that finds them. Deciding which of nine invoices for one month is the
-- real one is not a decision a migration should make on its own.

CREATE UNIQUE INDEX IF NOT EXISTS ux_invoice_one_per_tenant_month
    ON public.invoice (tenant_id, period_start)
    WHERE kind = 'invoice' AND status <> 'void';

CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_receipt_number
    ON public.payment (receipt_number)
    WHERE receipt_number IS NOT NULL;
