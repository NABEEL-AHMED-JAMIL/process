-- Billing: who is billed and how, the invoices a month closes into, the lines frozen on them,
-- the payments (a slip a workspace uploads, verified by the platform) and the documents
-- (invoice PDFs, receipts, credit notes, statements, slips) kept in the platform's config
-- bucket under billing/. Usage itself lives with the metering service (schema meter).

CREATE TABLE IF NOT EXISTS billing_account (
    billing_account_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL UNIQUE,
    legal_name VARCHAR(200),
    address VARCHAR(600),
    billing_email VARCHAR(200),
    tax_id VARCHAR(64),
    tax_rate_percent NUMERIC(6,3) NOT NULL DEFAULT 0,
    tax_label VARCHAR(24),
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    payment_terms_days INT NOT NULL DEFAULT 30,
    status VARCHAR(16) NOT NULL DEFAULT 'Active',
    date_created TIMESTAMP NOT NULL DEFAULT now(),
    date_updated TIMESTAMP,
    created_by BIGINT,
    updated_by BIGINT
);
COMMENT ON COLUMN billing_account.tax_id IS 'VAT/GST number; tax is applied only when this and a rate are set';

CREATE TABLE IF NOT EXISTS invoice (
    invoice_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    number VARCHAR(32) NOT NULL UNIQUE,
    kind VARCHAR(16) NOT NULL DEFAULT 'invoice',
    references_invoice_id BIGINT,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    subtotal NUMERIC(18,5) NOT NULL DEFAULT 0,
    tax_rate_percent NUMERIC(6,3) NOT NULL DEFAULT 0,
    tax NUMERIC(18,5) NOT NULL DEFAULT 0,
    total NUMERIC(18,5) NOT NULL DEFAULT 0,
    balance NUMERIC(18,5) NOT NULL DEFAULT 0,
    note VARCHAR(600),
    issued_at TIMESTAMP,
    due_at TIMESTAMP,
    paid_at TIMESTAMP,
    voided_at TIMESTAMP,
    pdf_object_key VARCHAR(512),
    rate_card_version INT,
    date_created TIMESTAMP NOT NULL DEFAULT now(),
    date_updated TIMESTAMP,
    created_by BIGINT,
    updated_by BIGINT
);
CREATE INDEX IF NOT EXISTS invoice_tenant_period ON invoice (tenant_id, period_start);
COMMENT ON COLUMN invoice.kind IS 'invoice | credit_note';
COMMENT ON COLUMN invoice.status IS 'draft | issued | partially_paid | paid | overdue | void';

CREATE TABLE IF NOT EXISTS invoice_line (
    invoice_line_id BIGSERIAL PRIMARY KEY,
    invoice_id BIGINT NOT NULL REFERENCES invoice (invoice_id) ON DELETE CASCADE,
    sort INT NOT NULL DEFAULT 0,
    meter VARCHAR(64),
    description VARCHAR(300) NOT NULL,
    quantity NUMERIC(18,6) NOT NULL DEFAULT 0,
    unit VARCHAR(24),
    per INT NOT NULL DEFAULT 1,
    unit_price NUMERIC(18,8) NOT NULL DEFAULT 0,
    amount NUMERIC(18,5) NOT NULL DEFAULT 0,
    period_label VARCHAR(32),
    manual BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE IF NOT EXISTS payment (
    payment_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    invoice_id BIGINT NOT NULL REFERENCES invoice (invoice_id),
    amount NUMERIC(18,5) NOT NULL,
    method VARCHAR(24) NOT NULL,
    reference VARCHAR(120),
    note VARCHAR(600),
    status VARCHAR(16) NOT NULL DEFAULT 'submitted',
    slip_object_key VARCHAR(512),
    receipt_number VARCHAR(32),
    submitted_by BIGINT,
    verified_by BIGINT,
    verified_at TIMESTAMP,
    received_at TIMESTAMP,
    date_created TIMESTAMP NOT NULL DEFAULT now()
);
COMMENT ON COLUMN payment.status IS 'submitted | verified | rejected';
COMMENT ON COLUMN payment.method IS 'bank | card | cash | credit_note | manual';

CREATE TABLE IF NOT EXISTS billing_document (
    billing_document_id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    invoice_id BIGINT,
    payment_id BIGINT,
    kind VARCHAR(24) NOT NULL,
    number VARCHAR(32),
    file_name VARCHAR(200) NOT NULL,
    content_type VARCHAR(100),
    size_bytes BIGINT,
    object_key VARCHAR(512) NOT NULL,
    amount NUMERIC(18,5),
    issued_at TIMESTAMP NOT NULL DEFAULT now(),
    created_by BIGINT
);
CREATE INDEX IF NOT EXISTS billing_document_tenant ON billing_document (tenant_id, issued_at);
COMMENT ON COLUMN billing_document.kind IS 'invoice | credit_note | receipt | statement | payment_slip';
