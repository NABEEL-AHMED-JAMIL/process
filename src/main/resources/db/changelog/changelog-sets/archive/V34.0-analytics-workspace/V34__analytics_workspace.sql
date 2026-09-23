-- The workspace: a saved analysis, a dashboard, and the widgets on it.
--
-- Three tables, and one decision that shapes all of them: the structured part of an analysis --
-- dimensions, measures, aggregation, filters, sort, top-N -- is kept as JSON in a text column and
-- not exploded into columns of its own. Spec 07 names eleven filter operators, nested AND/OR
-- groups, one to three dimensions and a top-N with an Other bucket, and every one of those is
-- still being designed. Columns would mean a changeset for each new operator, a nullable column
-- for each optional part, and a shape that has to be agreed between the migration and the Canvas
-- before either can move.
--
-- What that trades away, said plainly so nobody discovers it later: Postgres cannot answer
-- "which analyses group by department" or "which ones filter on country". Those become a scan and
-- a parse in the application, or they become impossible. That is the right trade only while
-- nothing needs to ask them -- the analyses are listed by name and opened one at a time -- and
-- the day something does, the answer is a real column populated from the JSON by a changeset,
-- not a LIKE over the text.
--
-- The counterpart rule, so the JSON does not become a place things hide: anything the SERVER has
-- to act on gets a column. The tenant, the author, the dataset location, the visualisation kind
-- and the widget's source are all columns, because a tenancy check or a foreign key cannot be
-- written against a substring.

-- ---------------------------------------------------------------------------------------------
-- analytics_dataset: no columns added, and the four the spec lists that are deliberately absent.
-- ---------------------------------------------------------------------------------------------
--
-- V31 created analytics_dataset and, until the service added alongside this changeset, nothing
-- had ever written a row to it. Making it live raised the question of the four columns spec 10
-- names that V31 left out with no reason recorded anywhere. Recorded here, because V31 is applied
-- and cannot be edited, and because "no reason written down" is how a column gets added twice.
--
-- file_count -- REFUSED. A cache with no invalidation story. Most datasets worth saving are a
-- folder read by glob, which is the case the feature exists for, and a partitioned output
-- directory gains files continuously; a count written at registration is wrong by the next
-- morning and wrong silently. The live count is one scan away for anyone who wants it.
--
-- total_size -- REFUSED, and more firmly than file_count. It goes stale the same way, but it is
-- also the number a person uses to decide whether a query is worth running, so being quietly
-- wrong about it is worse than not answering. Computing it honestly means listing the objects
-- through the storage adapter on every save, which puts a storage round trip inside a metadata
-- write for a figure nothing enforces.
--
-- schema_snapshot -- REFUSED, and this is the one with a real argument for it: every /schema call
-- costs a governed DuckDB session today. It is still a cache, and a worse-behaved one -- a file
-- can be rewritten in place under the same key, and a multi-file dataset reads with
-- union_by_name, so its column list changes as files land. A snapshot presented as "the columns"
-- would be a confident wrong answer where a DESCRIBE is a slow right one. The design record is
-- already explicit that the session cost is to be MEASURED before it is cached
-- (.ai/synthesis/analytics-studio.md, gap 17 in section 4: cache "only if the measurement still
-- justifies it afterwards"), and when it is cached it wants a cache with an expiry, not a durable
-- row that nothing invalidates.
--
-- last_profiled_at -- REFUSED, and this one is refused for a different reason, which is worth
-- separating. It is not a cache: "somebody profiled this at 14:02" is a fact about a past event
-- and cannot go stale. It is a fact worth keeping, and the honest reason it is not here is that
-- nothing can write it. Profiling is addressed by connection alias and path, not by a dataset id,
-- so there is no row in hand at the moment a profile runs -- and a column that no code path
-- writes is exactly the disease this changeset is curing. It should be added by whoever wires
-- profiling to a registered dataset, in the same changeset that gives it a writer.
--
-- analytics_dataset also gains no date_updated, and that stays true rather than being an
-- oversight carried forward: the service added with this changeset registers, lists, fetches and
-- deletes. There is no rename and no edit, so V32's note that the table "has no update path at
-- all" is still a description of the code and not a stale comment.

-- ---------------------------------------------------------------------------------------------
-- analytics_analysis
-- ---------------------------------------------------------------------------------------------

CREATE SEQUENCE IF NOT EXISTS analytics_analysis_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS analytics_analysis (
    analytics_analysis_id BIGINT       PRIMARY KEY,
    -- Nullable because a platform admin has no tenant of their own. As in analytics_dataset and
    -- analytics_query, a null here is NOT a row shared with every workspace: the entity filters on
    -- plain equality, which no null row satisfies. storage_connection's "or tenant_id is null"
    -- form is deliberately not used -- that publishes the platform's rows to every tenant, which
    -- is right for a catalogue the whole application resolves buckets through and wrong for one
    -- person's saved work.
    tenant_id             BIGINT,
    analysis_name         VARCHAR(255) NOT NULL,
    -- The same two columns, spelled the same way, as analytics_dataset and analytics_query, and
    -- deliberately NOT an analytics_dataset_id. Most analyses are built on a path the user is
    -- looking at and never got round to registering, and requiring a dataset row first would put
    -- an unrelated step in front of Save -- the same call V32 made and for the same reason.
    -- Carrying both an id and a path would be worse than either: one row with two ways to say
    -- where it reads from is one row that can disagree with itself.
    --
    -- The bucket is absent here for the third time in this module and for the same reason: the
    -- alias is stored, the bucket is read from storage_connection when the analysis is opened, so
    -- a connection repointed at a different bucket moves its saved analyses with it instead of
    -- leaving them quietly reading the old one.
    connection_alias      VARCHAR(255) NOT NULL,
    dataset_path          TEXT         NOT NULL,
    -- The one field lifted out of the JSON below. A listing shows it as an icon and a dashboard
    -- widget dispatches on it, so the server reads it on a path where parsing the whole
    -- configuration would be absurd. It must NOT also appear inside analysis_config: two places
    -- to say the same thing is one row that can disagree with itself.
    visualization_type    VARCHAR(32),
    -- Dimensions, measures, aggregation, filters, sort and top-N, as the JSON the Canvas sends.
    -- See the header for why this is one column and what it costs. Stored as submitted and never
    -- rewritten here: this table holds what somebody asked for, and the SQL it compiles to
    -- belongs to the engine's configuration on the day it runs.
    analysis_config       TEXT         NOT NULL,
    date_created          TIMESTAMP    NOT NULL DEFAULT now(),
    -- An analysis is edited in place -- that is what reopening one is for -- so unlike a dataset
    -- it needs a when to go with updated_by's who.
    date_updated          TIMESTAMP,
    created_by            BIGINT,
    updated_by            BIGINT,
    CONSTRAINT fk_analytics_analysis_tenant     FOREIGN KEY (tenant_id)  REFERENCES tenant (tenant_id),
    CONSTRAINT fk_analytics_analysis_created_by FOREIGN KEY (created_by) REFERENCES app_user (app_user_id),
    CONSTRAINT fk_analytics_analysis_updated_by FOREIGN KEY (updated_by) REFERENCES app_user (app_user_id),
    -- Not a lookup path: this exists so a widget can name (id, tenant_id) together and have the
    -- database refuse a cross-tenant reference. See the widget table for the whole argument.
    CONSTRAINT uk_analytics_analysis_id_tenant  UNIQUE (analytics_analysis_id, tenant_id)
);

-- The tenant filter is on every read of this table, and it is the only column it filters by.
CREATE INDEX IF NOT EXISTS idx_analytics_analysis_tenant_id ON analytics_analysis (tenant_id);

-- ---------------------------------------------------------------------------------------------
-- analytics_dashboard
-- ---------------------------------------------------------------------------------------------
--
-- READ THIS BEFORE BUILDING ON IT. These two tables are persistence for a dashboard, and they do
-- not settle the question of whether Analytics Studio should have a charting stack of its own at
-- all. .ai/synthesis/analytics-studio.md section 6, Q2 calls that "the most consequential open
-- question in this document", records that the user has been warned and has not decided, and
-- lays out a third option -- charts over a result, never assembled into a page -- that these
-- tables are simply unused under. Nothing here builds a chart, names a chart library or defines
-- a chart kind; a widget stores a title, a source and an opaque configuration string. If Q2 is
-- answered by pointing analytics results at the existing /reports pivot, what these tables hold
-- is a saved arrangement of results and the drawing still happens over there.

CREATE SEQUENCE IF NOT EXISTS analytics_dashboard_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS analytics_dashboard (
    analytics_dashboard_id BIGINT       PRIMARY KEY,
    -- Same reading as every other table in this module. Plain equality in the filter; a null is
    -- platform-owned, not everybody's.
    tenant_id              BIGINT,
    dashboard_name         VARCHAR(255) NOT NULL,
    -- A dashboard is the one thing here that is made to be shown to somebody who did not build
    -- it, and a name alone does not survive that. Nullable: a sentence nobody wrote is better
    -- absent than empty.
    dashboard_description  TEXT,
    date_created           TIMESTAMP    NOT NULL DEFAULT now(),
    date_updated           TIMESTAMP,
    created_by             BIGINT,
    updated_by             BIGINT,
    CONSTRAINT fk_analytics_dashboard_tenant     FOREIGN KEY (tenant_id)  REFERENCES tenant (tenant_id),
    CONSTRAINT fk_analytics_dashboard_created_by FOREIGN KEY (created_by) REFERENCES app_user (app_user_id),
    CONSTRAINT fk_analytics_dashboard_updated_by FOREIGN KEY (updated_by) REFERENCES app_user (app_user_id),
    CONSTRAINT uk_analytics_dashboard_id_tenant  UNIQUE (analytics_dashboard_id, tenant_id)
);

CREATE INDEX IF NOT EXISTS idx_analytics_dashboard_tenant_id ON analytics_dashboard (tenant_id);

-- The composite key a widget's cross-tenant guard needs on the saved-query side. analytics_query
-- is V32's table and V32 is applied, so the constraint is added here rather than edited in there.
-- It is not a lookup path and no query plan wants it -- (analytics_query_id) alone is already the
-- primary key -- so it earns its index only as a foreign-key target.
--
-- The one statement in this changeset that is not re-runnable: Postgres has no
-- ADD CONSTRAINT IF NOT EXISTS. That is fine because Liquibase applies a changeset once, and a
-- DO block to make it conditional would carry semicolons that splitStatements would cut in half.
ALTER TABLE analytics_query
    ADD CONSTRAINT uk_analytics_query_id_tenant UNIQUE (analytics_query_id, tenant_id);

-- ---------------------------------------------------------------------------------------------
-- analytics_dashboard_widget
-- ---------------------------------------------------------------------------------------------
--
-- One tile on a dashboard, pointing at exactly one saved thing: an analysis, or a saved query.
--
-- THE CROSS-TENANT RULE IS IN THE DATABASE AS WELL AS IN THE SERVICE, and the reason it is in
-- both is that this row is the module's first place where one saved object references another.
-- Everything before it was a leaf. A widget that names another workspace's analysis renders that
-- workspace's data inside a dashboard whose own tenant check passed, which is a cross-tenant read
-- that no single-row ownership check on the widget would ever catch.
--
-- So each of the three foreign keys below names (id, tenant_id) together, against a unique key on
-- the same pair. A widget owned by tenant A cannot reference a row owned by tenant B, because
-- there is no such pair to reference. The service checks the same thing before saving -- a
-- tenancy rule with exactly one enforcement point is one refactor away from having none -- but
-- the database version is the one that cannot be forgotten by a new call site.
--
-- What this does NOT cover, stated rather than glossed: Postgres treats a foreign key with any
-- NULL column as satisfied (MATCH SIMPLE), so a widget with a null tenant_id -- a platform
-- admin's -- is not constrained by these at all. That happens to agree with the ownership rule
-- the application already has, since a platform admin owns every row (TenantOwnership), but it
-- means the cascades below do not fire for those rows either. The service therefore deletes a
-- dashboard's widgets explicitly instead of relying on the cascade; the cascade is the backstop.
--
-- ON DELETE CASCADE on the two source keys is deliberate: a widget IS its source. It stores a
-- title and a rendering configuration and no copy of the analysis, so a widget whose analysis is
-- gone has nothing to draw. Note the consequence for the saved-query library, which is not
-- otherwise obvious from that code: deleting a saved query now also deletes any widget showing
-- it. That is the intended meaning and not a side effect to be worked around.

CREATE SEQUENCE IF NOT EXISTS analytics_dashboard_widget_seq START WITH 1000 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS analytics_dashboard_widget (
    analytics_dashboard_widget_id BIGINT       PRIMARY KEY,
    tenant_id                     BIGINT,
    analytics_dashboard_id        BIGINT       NOT NULL,
    widget_title                  VARCHAR(255) NOT NULL,
    -- Exactly one of these two is set; the check constraint below is what says so. There is
    -- deliberately no third "source_kind" column naming which: a label beside two columns is a
    -- label that can contradict them, and which one is set already answers the question.
    analytics_analysis_id         BIGINT,
    analytics_query_id            BIGINT,
    -- What to draw. Nullable because a widget over a saved query may be a table, which is the
    -- absence of a chart rather than a kind of one. An analysis-backed widget that leaves this
    -- null inherits the analysis's own visualization_type.
    visualization_type            VARCHAR(32),
    -- Axis choices, colours, size -- the rendering half, as JSON, for the same reason
    -- analysis_config is JSON. Nullable: a widget that takes every default has nothing to say
    -- here, and an empty object stored to avoid a null is a null with extra steps.
    widget_config                 TEXT,
    -- Where it sits. An integer rather than a grid coordinate pair because the arrangement is
    -- the front end's problem and a row/column/width/height quartet would freeze one layout
    -- engine's model into the schema before a layout engine has been chosen. A finer position
    -- belongs in widget_config until something server-side needs to read it.
    display_order                 INT          NOT NULL DEFAULT 0,
    date_created                  TIMESTAMP    NOT NULL DEFAULT now(),
    date_updated                  TIMESTAMP,
    created_by                    BIGINT,
    updated_by                    BIGINT,
    -- One source, and one only. Neither set is a widget with nothing to draw; both set is a row
    -- that can disagree with itself about what it shows.
    CONSTRAINT ck_analytics_dashboard_widget_one_source CHECK (
        (analytics_analysis_id IS NOT NULL AND analytics_query_id IS NULL)
        OR (analytics_analysis_id IS NULL AND analytics_query_id IS NOT NULL)
    ),
    CONSTRAINT fk_analytics_dashboard_widget_dashboard
        FOREIGN KEY (analytics_dashboard_id, tenant_id)
        REFERENCES analytics_dashboard (analytics_dashboard_id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_analytics_dashboard_widget_analysis
        FOREIGN KEY (analytics_analysis_id, tenant_id)
        REFERENCES analytics_analysis (analytics_analysis_id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_analytics_dashboard_widget_query
        FOREIGN KEY (analytics_query_id, tenant_id)
        REFERENCES analytics_query (analytics_query_id, tenant_id) ON DELETE CASCADE,
    CONSTRAINT fk_analytics_dashboard_widget_created_by FOREIGN KEY (created_by) REFERENCES app_user (app_user_id),
    CONSTRAINT fk_analytics_dashboard_widget_updated_by FOREIGN KEY (updated_by) REFERENCES app_user (app_user_id)
);

-- Postgres does not index a foreign key for you, and all three of these are scanned on every
-- delete of the row they point at. The first one doubles as the read path -- a widget is never
-- fetched except as "the widgets on this dashboard, in order" -- which is also why this table
-- gets no tenant_id-only index: there is no listing that starts from the tenant alone.
CREATE INDEX IF NOT EXISTS idx_analytics_dashboard_widget_dashboard
    ON analytics_dashboard_widget (analytics_dashboard_id, tenant_id, display_order);
CREATE INDEX IF NOT EXISTS idx_analytics_dashboard_widget_analysis
    ON analytics_dashboard_widget (analytics_analysis_id, tenant_id);
CREATE INDEX IF NOT EXISTS idx_analytics_dashboard_widget_query
    ON analytics_dashboard_widget (analytics_query_id, tenant_id);

COMMENT ON TABLE analytics_analysis IS
    'A saved Analytics Canvas analysis: a storage connection alias, a path inside it, and the dimension/measure/filter configuration as JSON. The bucket is NOT stored -- it comes from the connection record at resolve time, so repointing a connection moves its saved analyses with it.';
COMMENT ON COLUMN analytics_analysis.analysis_config IS
    'Dimensions, measures, aggregation, filters, sort and top-N as JSON. One column rather than twenty because the shape is still moving; the cost is that Postgres cannot answer "which analyses group by department".';
COMMENT ON TABLE analytics_dashboard IS
    'Dashboard metadata: a name, a description and its owner. Holds no chart definition -- a widget row does that -- and does not settle whether Analytics Studio charts belong here or in the existing /reports pivot.';
COMMENT ON TABLE analytics_dashboard_widget IS
    'One tile on a dashboard, pointing at exactly one saved analysis or one saved query. Both foreign keys name (id, tenant_id) so the database refuses a widget that references another workspace''s row.';
COMMENT ON COLUMN analytics_dashboard_widget.display_order IS
    'Position in the dashboard, coarsely. A finer layout belongs in widget_config until something server-side needs to read it.';
