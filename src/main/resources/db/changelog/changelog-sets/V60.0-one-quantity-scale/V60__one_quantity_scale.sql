-- MIG-197: one scale for every quantity from the ledger to the bill, numeric(24,6), the meter's own
-- (etl.meter.store widens meter.usage_event, usage_daily and rate_card_item to the same).
-- included_quantity and billable_quantity were numeric(20,5), so a six-place quantity lost a digit
-- crossing into them, and at an allowance or tier boundary that digit moves a quantity across the band.
-- quantity was numeric(18,6), which stops a byte meter at about 1 TB for a month.
-- Every change widens or adds a place, so no stored value changes.
-- No semicolons in these comments: the changeset splits statements on them.
ALTER TABLE invoice_line ALTER COLUMN quantity TYPE NUMERIC(24,6);

ALTER TABLE invoice_line ALTER COLUMN included_quantity TYPE NUMERIC(24,6);

ALTER TABLE invoice_line ALTER COLUMN billable_quantity TYPE NUMERIC(24,6);
