-- A dataset somebody named and kept: one storage connection alias, and one path inside it.
--
-- The first table Analytics Studio owns. It is the dataset rather than the query on purpose: a
-- saved query, a chart and a benchmark result all have to say which data they are about, and if
-- each of those arrives with its own way of naming a location there end up being three spellings
-- of "the CSVs in that folder" and no way to answer "what else points at this?".
--
-- The bucket is deliberately absent, and that is the whole design of the row. A dataset stores the
-- connection ALIAS; the bucket is read from storage_connection at the moment the dataset is
-- opened. Copying it here would make this a second source of truth for it, so a connection later
-- repointed at a different bucket would leave every saved dataset quietly reading the old one --
-- and the module's central property, that a caller names a connection and never a bucket, would
-- stop being true of saved datasets. Same reason there are no credentials, no endpoint and no
-- region here: everything needed to reach the data already lives on the connection.

CREATE SEQUENCE IF NOT EXISTS analytics_dataset_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS analytics_dataset (
    analytics_dataset_id BIGINT       PRIMARY KEY,
    -- Nullable because a platform admin has no tenant of their own. Unlike storage_connection,
    -- a null here is NOT a row shared with every workspace: the entity filters on plain equality,
    -- which no null row satisfies, so a platform-owned dataset stays visible to platform admins
    -- alone. A saved dataset is one person's work, not a catalogue anyone else resolves through.
    tenant_id            BIGINT,
    dataset_name         VARCHAR(255) NOT NULL,
    -- The alias the object browser and every bucket path in the app already pass around, not an
    -- id: it is what the caller sends and what DatasetResolver looks a connection up by.
    connection_alias     VARCHAR(255) NOT NULL,
    -- TEXT rather than VARCHAR because an object key is arbitrarily long, and a folder dataset
    -- keeps its glob (orders/2026/*.csv) as the path -- the pattern IS the dataset.
    dataset_path         TEXT         NOT NULL,
    -- CSV, TSV, JSON or PARQUET. Recorded so a list of saved datasets can show what each one is
    -- without opening it; the reader still derives the format from the path every time it resolves
    -- one, so a wrong value here cannot make the engine read a file with the wrong reader.
    dataset_format       VARCHAR(24)  NOT NULL,
    date_created         TIMESTAMP    NOT NULL DEFAULT now(),
    created_by           BIGINT,
    updated_by           BIGINT,
    CONSTRAINT fk_analytics_dataset_tenant     FOREIGN KEY (tenant_id)  REFERENCES tenant (tenant_id),
    CONSTRAINT fk_analytics_dataset_created_by FOREIGN KEY (created_by) REFERENCES app_user (app_user_id),
    CONSTRAINT fk_analytics_dataset_updated_by FOREIGN KEY (updated_by) REFERENCES app_user (app_user_id)
);

-- The tenant filter is on every read of this table, and it is the only column it filters by.
CREATE INDEX IF NOT EXISTS idx_analytics_dataset_tenant_id ON analytics_dataset (tenant_id);

-- No uniqueness on (tenant_id, connection_alias, dataset_path) on purpose. The same folder read
-- two ways -- "last night's export" and "the one with the bad rows" -- is two datasets to the
-- people who saved them, and a constraint here would refuse the second one for no benefit.

COMMENT ON TABLE analytics_dataset IS
    'A named dataset in Analytics Studio: a storage connection alias plus a path inside it. Saved queries, charts and benchmark results all point at one of these rather than naming a location themselves.';
COMMENT ON COLUMN analytics_dataset.connection_alias IS
    'The storage connection this dataset is read through. The bucket is NOT stored here -- it comes from the connection record at resolve time, so repointing a connection moves its saved datasets with it.';
COMMENT ON COLUMN analytics_dataset.dataset_path IS
    'The key inside the connection, or a glob when the dataset is a folder read as one table.';
