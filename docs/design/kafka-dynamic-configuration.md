# Dynamic, Multi-Tenant Kafka Configuration — Design Analysis

Status: **proposal / not yet implemented**. Grounded in the actual current code (not assumed) —
every file/field named below was read directly from this repo before writing this doc.

## Missing information (per your instruction, flagged before any assumption)

You asked me not to assume columns on `kafka_connection_profile` beyond what you've provided.
I read the table from the live entity instead of guessing — its **current, real** columns are:

```
kafka_connection_profile_id, profile_name, bootstrap_servers, security_protocol,
sasl_mechanism, sasl_username, sasl_password (AES‑256‑GCM ciphertext), connection_active,
status, date_created
```

Everything below that adds columns is clearly marked **NEW** and is a recommendation for you
to confirm, not something already in the schema.

One real open decision I can't resolve without your input — flagged here, with my recommended
default, rather than silently picking one:

> **Should `source_task_type` become tenant-owned (each tenant gets its own copy), or should it
> stay a shared/global catalog with a per-tenant *routing override*?**
> Today `source_task_type` has no `tenant_id` at all — it's shared master data referenced by
> every tenant's `source_task` rows. Forcing it tenant-owned means duplicating catalog rows
> per tenant. I recommend keeping it shared and adding a thin **override table**
> (`tenant_task_type_kafka_route`, below) instead — it satisfies every requirement you listed
> without that duplication, and is the design the rest of this document assumes. If you'd
> rather each tenant maintain its own task-type catalog, say so and §1/§2 change to a direct
> `tenant_id` + `kafka_connection_profile_id` pair on `source_task_type` itself (simpler, but
> loses "same task type, different tenants, different clusters" without row duplication).

---

## Task 1 — Remove `SCHEMA_PAYLOAD` / `IS_SCHEMA_REGISTER`

### What they actually do today

Confirmed by grep across the whole backend: **these two fields are pure display metadata.**
There is no Confluent/Avro schema-registry client anywhere in the codebase (`grep -rn
"SchemaRegistry\|KafkaAvroSerializer\|schema.registry"` → zero hits). The producer
(`KafkaTemplateProvider.producerProps`) always uses `StringSerializer` for key and value,
unconditionally. `isSchemaRegister` is derived, not user-set — `SettingServiceImpl` computes it
as `!isNull(schemaPayload)` on every save, i.e. it's just "is schemaPayload non-empty," not an
independent flag or an integration switch. **Removing both is safe: zero effect on message
publishing, topic provisioning, or any other runtime behavior.**

### Dependent code — backend

| Layer | File | What to change |
|---|---|---|
| Entity | `process/model/pojo/SourceTaskType.java` | remove `isSchemaRegister` field + getter/setter, `schemaPayload` field + getter/setter |
| DTO | `process/model/dto/SourceTaskTypeDto.java` | remove `isSchemaRegister`, `schemaPayload` |
| Projection | `process/model/projection/SourceTaskTypeProjection.java` | remove `getSchemaRegister()` |
| Repository | `process/model/repository/SourceTaskTypeRepository.java` | `fetchAllSourceTaskType()`'s native SQL selects `stt.is_schema_register`, `stt.schema_payload` — drop both from the `select` list |
| Native query | `process/model/service/impl/QueryService.java:112-113` | `listSourceTaskQuery()` selects `stt.is_schema_register, stt.schema_payload` — drop both, and shift the positional index parsing in `SourceTaskServiceImpl.listSourceTask()` (`obj[index]` walks a fixed column order) down by two |
| Service | `process/model/service/impl/SettingServiceImpl.java` | `mapSourceTaskTypeProjectionToDto` (line 136-137), `addSourceTaskType`→`getSourceTaskType()` (line 437-438), `updateSourceTaskType` (line 191, 193) — remove all four `set*` calls |
| Service | `process/model/service/impl/SourceJobServiceImpl.java:542`, `SourceTaskServiceImpl.java:307,621` | `getSourceTaskTypeDto()` mappers — remove `setSchemaRegister(...)` calls |

No `SourceTaskTypeRestApi` request/response contract changes beyond the DTO shrinking — Jackson's
`@JsonIgnoreProperties(ignoreUnknown=true)` (already on every DTO in this codebase) means an old
frontend build sending these fields in a request body won't break; the backend just ignores them.

### Dependent code — frontend

| File | What to remove |
|---|---|
| `src/app/_component/setting/source-task-type/source-task-type.component.ts` | `schemaPayload` form control (3 occurrences: init, edit-populate, reset) |
| `src/app/_component/setting/source-task-type/source-task-type.component.html` | the "SchemaPayload" `<textarea>` + its validation block (lines 68-75) |
| `src/app/_component/setting/setting.component.html` | the `schemaRegister` pill column (lines 63-67) in the Source Task Type table |
| `src/app/_component/source-task/source-task.component.html:272-277` | the "Schema Payload" `<pre>` block in the row-expand panel |
| `src/app/_component/source-job/source-job.component.html:333-338` | the "Schema Payload" `<pre>` block in the row-expand panel |
| `src/app/_models/object.ts:24-25` | `schemaRegister?`, `schemaPayload?` from the `SourceTaskType` interface |

### SQL migration (Liquibase — matches this project's existing convention)

This project uses Hibernate `ddl-auto=update` for additive schema changes but Liquibase for
**drops** (Hibernate never removes a column on its own, so an explicit migration is required or
the columns become permanent orphans). Follow the existing pattern
(`db/changelog/yaml/V10.0-...yaml` + a paired `.sql` file):

```yaml
# src/main/resources/db/changelog/yaml/V11.0-kafka-dynamic-config.yaml
databaseChangeLog:
  - changeSet:
      id: 11.0-drop-source-task-type-schema-fields
      author: nabeel.amd93
      description: "Remove unused schema_payload/is_schema_register (display-only, no schema-registry integration ever existed)"
      changes:
        - sqlFile:
            path: ../changelog-sets/V11.0-kafka-dynamic-config/V11__drop_schema_fields.sql
            relativeToChangelogFile: true
            splitStatements: true
            endDelimiter: ;
```

```sql
-- V11__drop_schema_fields.sql
ALTER TABLE source_task_type DROP COLUMN IF EXISTS is_schema_register;
ALTER TABLE source_task_type DROP COLUMN IF EXISTS schema_payload;
```

### Data impact

Both columns' data is discarded. Since it was never read by anything except the two screens
above, this is a pure UI/display loss, not a functional one. **Take a `pg_dump` of
`source_task_type` before running this migration** — it's the one irreversible step in this
whole project (see §8, Rollback).

### Replacement functionality needed?

None. If you *do* want real schema-registry integration later (Avro/Protobuf payload
validation), that's a separate, larger feature — a proper `schema_registry_url` +
`KafkaAvroSerializer` wiring — and shouldn't be conflated with these two dead columns.

---

## 1. Database design

### Recommended relationship

**Many-to-one, `source_task_type` → `kafka_connection_profile`, for the default binding — plus
a tenant-scoped override join table that makes the *effective*, resolved relationship
many-to-many once the tenant dimension is included.** This is what satisfies every one of your
bullets simultaneously:

- "reusable by multiple source task types" → many-to-one (FK on the *many* side).
- "same task type, different tenant, different cluster" (your Client A/B/C example, where in
  practice they'd often be running the *same kind* of task) → needs the tenant dimension in the
  routing decision, which a bare FK on `source_task_type` can't express without duplicating rows.
- "avoid modifying `source_task_type` for future config" → the override table and the profile's
  own extensible columns absorb future change; `source_task_type` only ever gains the one FK.

```
tenant  ───────────< kafka_connection_profile >───────────┐
   │  1                        (tenant_id nullable = shared/platform default)
   │                                                        │ 1
   │                                                        │
   │                                                        │ (default binding)
   └────< tenant_task_type_kafka_route >──────────── source_task_type
              (tenant_id, source_task_type_id) unique          (kafka_connection_profile_id = its own default)
              → kafka_connection_profile_id
```

### Schema changes

**`kafka_connection_profile` — new columns:**

| Column | Type | Notes |
|---|---|---|
| `tenant_id` | `BIGINT NULL` | FK → `tenant.tenant_id`. **NULL = platform-wide/shared profile** (same convention as `app_user.tenant_id` for PLATFORM_ADMIN elsewhere in this app) — lets you seed a default/shared cluster every tenant can use before they configure their own. |
| `is_default` | `BOOLEAN NOT NULL DEFAULT FALSE` | Replaces `connection_active`'s meaning: "this tenant's default profile" instead of "the one cluster the whole app uses." At most one `TRUE` per `tenant_id` (partial unique index below). `connection_active` is deprecated in place (kept as an alias/synonym for one release, then dropped — see §8). |
| `environment_label` | `VARCHAR(64) NULL` | Free-text, e.g. `local`, `staging`, `prod-remote-us`. Descriptive only — helps a human pick the right profile; not used in resolution logic. |
| `ssl_keystore_location` | `VARCHAR(500) NULL` | Server-side path/URI to the keystore file (see §3 — never a raw upload stored as a DB blob). |
| `ssl_keystore_password_enc` | `TEXT NULL` | AES‑256‑GCM ciphertext, same `EncryptionUtil` already used for `sasl_password`. |
| `ssl_key_password_enc` | `TEXT NULL` | ciphertext |
| `ssl_truststore_location` | `VARCHAR(500) NULL` | server-side path/URI |
| `ssl_truststore_password_enc` | `TEXT NULL` | ciphertext |
| `ssl_endpoint_identification_algorithm` | `VARCHAR(32) NULL` | e.g. `https` or empty string to disable hostname verification (rare, dev-only) |
| `additional_properties` | `JSONB NULL` | Free-form **non-secret** Kafka client properties (`request.timeout.ms`, `retries`, `delivery.timeout.ms`, etc.) — the extensibility escape hatch so future Kafka options never require another migration. **Contract: never put secrets in this JSON** — they get their own encrypted column or a `secret_ref` (§3). |
| `connection_status` | `VARCHAR(16) NOT NULL DEFAULT 'UNTESTED'` | `UNTESTED` \| `SUCCESS` \| `FAILED` |
| `last_tested_at` | `TIMESTAMP NULL` | |
| `last_test_message` | `TEXT NULL` | human-readable result of the last test (success detail or failure reason) |

**`source_task_type` — new column:**

| Column | Type | Notes |
|---|---|---|
| `kafka_connection_profile_id` | `BIGINT NULL` | FK → `kafka_connection_profile`. This task type's *default* cluster when no tenant override exists. `NULL` = fall through to the tenant's own default (see resolution order, §2). |

**New table — tenant routing override:**

```sql
CREATE TABLE tenant_task_type_kafka_route (
    tenant_task_type_kafka_route_id BIGINT PRIMARY KEY,
    tenant_id                       BIGINT NOT NULL REFERENCES tenant(tenant_id),
    source_task_type_id             BIGINT NOT NULL REFERENCES source_task_type(source_task_type_id),
    kafka_connection_profile_id     BIGINT NOT NULL REFERENCES kafka_connection_profile(kafka_connection_profile_id),
    date_created                    TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_task_type UNIQUE (tenant_id, source_task_type_id)
);
```

Sequence-backed ID, matching every other table in this project (`GenericGenerator` +
`SequenceStyleGenerator`, initial value 1000) rather than a raw `SERIAL`.

### Foreign keys, indexes, unique constraints

```sql
-- kafka_connection_profile
ALTER TABLE kafka_connection_profile
    ADD COLUMN tenant_id BIGINT NULL,
    ADD CONSTRAINT fk_kcp_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(tenant_id);

CREATE INDEX idx_kcp_tenant_id ON kafka_connection_profile(tenant_id);

-- at most one default profile per tenant (and at most one platform-wide default, tenant_id IS NULL)
CREATE UNIQUE INDEX uq_kcp_default_per_tenant
    ON kafka_connection_profile (COALESCE(tenant_id, -1))
    WHERE is_default = TRUE AND status = 'Active';

-- source_task_type
ALTER TABLE source_task_type
    ADD COLUMN kafka_connection_profile_id BIGINT NULL,
    ADD CONSTRAINT fk_stt_kafka_profile FOREIGN KEY (kafka_connection_profile_id)
        REFERENCES kafka_connection_profile(kafka_connection_profile_id);

CREATE INDEX idx_stt_kafka_profile_id ON source_task_type(kafka_connection_profile_id);

-- tenant_task_type_kafka_route
CREATE INDEX idx_ttkr_tenant_id ON tenant_task_type_kafka_route(tenant_id);
CREATE INDEX idx_ttkr_task_type_id ON tenant_task_type_kafka_route(source_task_type_id);
CREATE INDEX idx_ttkr_profile_id ON tenant_task_type_kafka_route(kafka_connection_profile_id);
```

A cross-table CHECK ("the route's profile must belong to the same tenant, or be a shared
NULL-tenant profile") isn't expressible as a portable Postgres constraint without a trigger —
enforce it in the service layer (same place every other cross-entity ownership check in this
codebase already lives, e.g. `TransactionServiceImpl.findByTaskDetailIdAndTaskStatus`'s tenant
filter added earlier this session). A `BEFORE INSERT/UPDATE` trigger is a reasonable belt-and-
suspenders addition if you want DB-level enforcement independent of application code.

### Example data — three clients, three clusters (your example, made concrete)

```sql
-- Client A: remote cluster, SASL_SSL
INSERT INTO kafka_connection_profile
  (kafka_connection_profile_id, tenant_id, profile_name, environment_label, bootstrap_servers,
   security_protocol, sasl_mechanism, sasl_username, sasl_password, is_default, status, date_created)
VALUES
  (2001, 101, 'Client A Prod Cluster', 'prod-remote-us',
   'kafka-a-1.clienta.example.com:9093,kafka-a-2.clienta.example.com:9093',
   'SASL_SSL', 'SCRAM-SHA-512', 'client-a-svc', '<AES-GCM ciphertext>', TRUE, 'Active', now());

-- Client B: remote cluster, mTLS (SSL client cert, no SASL)
INSERT INTO kafka_connection_profile
  (kafka_connection_profile_id, tenant_id, profile_name, environment_label, bootstrap_servers,
   security_protocol, ssl_keystore_location, ssl_keystore_password_enc, ssl_key_password_enc,
   ssl_truststore_location, ssl_truststore_password_enc, is_default, status, date_created)
VALUES
  (2002, 102, 'Client B EU Cluster', 'prod-remote-eu',
   'kafka-b.clientb.example.eu:9093', 'SSL',
   '/etc/kafka-secrets/client-b/keystore.jks', '<enc>', '<enc>',
   '/etc/kafka-secrets/client-b/truststore.jks', '<enc>', TRUE, 'Active', now());

-- Client C: local/on-prem cluster, plaintext (dev/internal network only)
INSERT INTO kafka_connection_profile
  (kafka_connection_profile_id, tenant_id, profile_name, environment_label, bootstrap_servers,
   security_protocol, is_default, status, date_created)
VALUES
  (2003, 103, 'Client C Local Cluster', 'local',
   'kafka-local.clientc.internal:9092', 'PLAINTEXT', TRUE, 'Active', now());

-- Platform-wide shared default (tenant_id NULL) -- used by any tenant with no profile of their own
INSERT INTO kafka_connection_profile
  (kafka_connection_profile_id, tenant_id, profile_name, environment_label, bootstrap_servers,
   security_protocol, is_default, status, date_created)
VALUES
  (2000, NULL, 'Platform Shared Default', 'shared',
   'kafka-shared.platform.internal:9092', 'PLAINTEXT', TRUE, 'Active', now());

-- Source task type's own default binding (e.g. the existing "ETL Scrapping Pipeline" type)
UPDATE source_task_type SET kafka_connection_profile_id = 2000 WHERE source_task_type_id = 1011;

-- Client A overrides that same shared task type to publish to their own cluster instead
INSERT INTO tenant_task_type_kafka_route
  (tenant_task_type_kafka_route_id, tenant_id, source_task_type_id, kafka_connection_profile_id, date_created)
VALUES (3001, 101, 1011, 2001, now());
```

---

## 2. Backend design

### Runtime resolution order

For a given `SourceJob` about to publish (it carries `tenantId` and, via its `SourceTask`, a
`sourceTaskTypeId`):

1. `tenant_task_type_kafka_route` for `(tenantId, sourceTaskTypeId)` — a tenant-specific override.
2. `source_task_type.kafka_connection_profile_id` — the task type's own default.
3. `kafka_connection_profile` where `tenant_id = tenantId AND is_default = TRUE AND status = 'Active'` — the tenant's own default profile.
4. `kafka_connection_profile` where `tenant_id IS NULL AND is_default = TRUE AND status = 'Active'` — the platform-wide shared default.
5. The existing env-var-driven Spring Boot autoconfigured `KafkaTemplate` (`SPRING_KAFKA_BOOTSTRAP_SERVERS`) — **today's only behavior, preserved as the final fallback** so nothing breaks before any profile is configured.

Each step short-circuits on the first `Active` hit. This is a direct generalization of what
`KafkaTemplateProvider.getTemplate()` already does (steps 4→5 are exactly its current logic
today, just re-scoped from "the one global active profile" to "the platform-wide default").

### Spring Boot architecture

```
KafkaConnectionResolver              (NEW)
  .resolve(tenantId, sourceTaskTypeId) -> Optional<KafkaConnectionProfile>
       implements the 5-step order above, read-only, no producer creation

KafkaTemplateRegistry                (renamed/rewritten from KafkaTemplateProvider)
  .getTemplate(KafkaConnectionProfile)  -> KafkaTemplate<String,String>
       ConcurrentHashMap<Long profileId, CachedProducer> keyed by profile id
       lazily builds+caches a DefaultKafkaProducerFactory per profile on first use
       .invalidate(Long profileId)   -- closes and evicts one entry (on update/disable/delete)
       .invalidateAll()              -- e.g. after a bulk credential rotation

ProducerBulkEngine (existing)
       Long profileId = kafkaConnectionResolver.resolve(job.tenantId, taskType.id)...
       kafkaTemplate = kafkaTemplateRegistry.getTemplate(profile)   // cached, not rebuilt per message
```

This is the direct fix for **"how to prevent creating a new Kafka producer for every message"**
and **"connection pooling/caching strategy"**: today's `KafkaTemplateProvider` already caches
*one* producer (a `volatile` single-slot cache) — the only change needed is widening that single
slot into a map keyed by profile id, since there can now be many concurrently-active profiles
(one per tenant, potentially) instead of exactly one for the whole app. `DefaultKafkaProducerFactory`
is itself thread-safe and designed to be reused across the JVM's lifetime — the cache exists
specifically so `KafkaProducer`'s expensive connection/metadata-fetch startup happens once per
cluster, not once per message.

### Lifecycle: register / update / disable / delete

| Action | Behavior |
|---|---|
| **Register** (`POST` a new profile) | Row inserted with `status=Active`, `connection_status=UNTESTED`. No producer built yet — lazy on first actual use (or immediately if the UI's "Test Connection" is used, which builds a throwaway `AdminClient`, not a producer). |
| **Update** | Row updated. **Must** call `kafkaTemplateRegistry.invalidate(profileId)` so the next publish rebuilds the producer with the new credentials/brokers — mirrors the existing `updateProfile`'s `if (connectionActive) kafkaTemplateProvider.invalidate()` call, just keyed per-profile instead of global. |
| **Disable** (`status=Inactive`) | Resolution steps 1-4 all filter on `status='Active'`, so a disabled profile silently drops out of routing and resolution falls through to the next step (tenant default → platform default → env fallback) rather than failing the job outright. Existing cached producer for that profile is invalidated immediately, not left to leak. |
| **Delete** (soft-delete, `status=Delete`, matching this app's existing pattern — see `KafkaConnectionProfileServiceImpl.deleteProfile`) | Same as disable, plus: **block the delete with a clear error if the profile is still referenced** by any `source_task_type.kafka_connection_profile_id` or `tenant_task_type_kafka_route` row (same "can't delete what's in use" pattern this app already applies elsewhere, e.g. `SourceTaskServiceImpl.deleteSourceTask`'s cascade-to-jobs). Don't silently null out those references. |

### Preventing a stale/wrong producer

`KafkaTemplateRegistry.invalidate(profileId)` must be called from every write path that can
change a profile's connectivity-relevant fields: `updateProfile`, `deactivateProfile` (formerly
`deactivateActiveProfile`), `deleteProfile`. This matches — and generalizes — what
`KafkaTemplateProvider.invalidate()` already does today for the single global slot.

### Multi-client scale

A `ConcurrentHashMap` of lazily-built producers scales fine to dozens–low hundreds of distinct
active profiles (each `KafkaProducer` holds a handful of TCP connections + a background I/O
thread — not free, but not heavy either). If you eventually have *many hundreds* of tenants each
with their own cluster, add an LRU eviction policy (e.g. Caffeine cache with `maximumSize` +
`removalListener` that calls `producerFactory.destroy()`) so idle tenants' producers get closed
instead of accumulating forever. Not needed at today's scale — flagging it as the natural next
step if tenant count grows.

---

## 3. Kafka security

**Current state (already production, already correct for what it covers):** `sasl_password` is
never stored plaintext — `EncryptionUtil` (AES-256-GCM, random IV per encryption, key from the
`LOOKUP_ENCRYPTION_KEY` env var, never committed/hardcoded) encrypts it before persisting, and
`KafkaConnectionProfileDto` never sends the value back to the frontend (only a
`saslPasswordConfigured: boolean`). **Recommendation: extend this exact, already-proven pattern
to the new SSL secret fields** (`ssl_keystore_password_enc`, `ssl_key_password_enc`,
`ssl_truststore_password_enc`) rather than introducing a second mechanism — same
`EncryptionUtil.encrypt/decrypt`, same "DTO exposes only a `*Configured: boolean`, never the
value" contract.

| Requirement | How it's covered |
|---|---|
| SSL/TLS | `security_protocol = SSL` or `SASL_SSL`; `ssl_keystore_location`/`ssl_truststore_location` point at files already on the app server's filesystem or a mounted secret volume (Docker secret, K8s Secret volume mount) — **the DB stores a path/reference, never the certificate bytes themselves.** |
| SASL | `security_protocol` starting `SASL_`; `sasl_mechanism` (`PLAIN`, `SCRAM-SHA-256`, `SCRAM-SHA-512` — already supported, see `KAFKA_SASL_MECHANISMS` on the frontend and the login-module selection in `KafkaTemplateProvider.commonClientProps`) |
| Username/password | `sasl_username` (plain, not secret) + `sasl_password_enc` (AES-GCM ciphertext) |
| Certificates | keystore/truststore location + encrypted passwords, above. Certificate **files themselves** should live outside the database entirely — see below. |
| SASL mechanisms | Already an explicit allow-list (`KAFKA_SECURITY_PROTOCOLS`, `KAFKA_SASL_MECHANISMS` on the frontend) — extend the mechanism list if you need Kerberos (`GSSAPI`) or OAUTHBEARER later; that's an `additional_properties` JSON addition, not a schema change. |
| Other properties | `additional_properties JSONB` — non-secret tuning knobs only. |

### Where certificate *files* actually live

Don't store PEM/JKS file bytes as a DB column (BLOB-in-DB is an operational headache — backups
balloon, and it's the wrong tool for something Kafka's client libraries want as a filesystem
path anyway). Two reasonable options, in order of recommendation:

1. **Admin-provisioned filesystem path** (simplest, matches this project's current
   ops maturity): certs are placed on the app server / mounted into the container by whoever
   provisions the environment (same as how `LOOKUP_ENCRYPTION_KEY` itself is provisioned today).
   The UI's "keystore location" field is just a path the admin types in, validated to exist at
   Test-Connection time. No new infrastructure.
2. **Object storage** (this app already has a Bucket Browser / MinIO-S3-Azure abstraction —
   `StorageBrowserServiceImpl`): upload the cert to a dedicated, non-browsable bucket via the
   existing `ObjectStorageService`, store the object key in `ssl_keystore_location`, and have
   the backend download-and-cache it to a local temp path the first time a profile's producer is
   built. More self-service (a tenant admin can upload their own cert through the UI) at the
   cost of a bit more plumbing.

Recommend starting with (1) and moving to (2) only if self-service cert upload becomes an actual
requirement — don't build it speculatively.

### Secret-management recommendation

**Keep `EncryptionUtil` (AES-256-GCM, app-level) as the baseline — it already satisfies "not
plaintext in the DB" and is already running in production for `sasl_password`.** For a stronger
posture as this scales to more tenants/clusters, the natural evolution (not required for this
change, but worth planning for) is a **secret reference indirection**: add a nullable
`secret_ref VARCHAR` column that, when set, means "fetch the real secret from
Vault/AWS Secrets Manager/GCP Secret Manager at `secret_ref` instead of decrypting
`sasl_password`/`ssl_*_password_enc` locally." The resolver checks `secret_ref` first, falls
back to the local encrypted column — so both mechanisms coexist during a gradual migration, and
nothing about today's working AES approach has to change to add this later.

---

## 4. Kafka connection validation

**Already exists and is close to correct** — `KafkaConnectionProfileServiceImpl.testConnection`
builds an `AdminClient` from the profile's exact client properties (`KafkaTemplateProvider
.commonClientProps`, the *same* config path the real producer uses — good, no "test config"
vs "real config" drift) and calls `describeCluster()`. Recommended extensions:

```
User creates/edits a Kafka Connection Profile
          │
          ▼
Frontend "Test Connection" (works pre-save, from in-flight form values)
          │
          ▼
Backend: AdminClient.describeCluster() with a bounded timeout (10s, already the case)
          │
          ├─ success → optionally also describeTopics() if a topic was supplied
          │             (reuses the existing testTopic endpoint's logic)
          ▼
Persist: connection_status = SUCCESS|FAILED, last_tested_at = now(), last_test_message = <detail>
          │
          ▼
Response to frontend: pass/fail + human-readable reason (broker unreachable / auth rejected / timeout / ...)
```

**What to validate:**
- Cluster reachable (`describeCluster()` — already done).
- Broker count sane (`nodes().size() > 0` — already surfaced in the success message).
- Auth actually accepted (SASL/SSL handshake failures surface as `AuthenticationException`/
  `SslAuthenticationException` from the same call — already caught generically; recommend
  branching the catch to give a specific "authentication rejected" vs "network unreachable"
  message instead of one generic failure string, since those need very different fixes).
- Optionally, the specific topic(s) a `source_task_type` will publish to (`describeTopics` —
  the `testTopic` endpoint already does this, just not yet tied to a specific profile+topic pair
  in one call).

**Failure reporting:** return the real exception message (already does), but classify it
(`TimeoutException` → "Broker unreachable or network blocked", `SaslAuthenticationException` →
"Authentication rejected — check username/password/mechanism", `SslAuthenticationException` →
"TLS handshake failed — check certificates", generic → raw message) so the UI can show something
actionable rather than a raw Kafka client stack trace fragment.

---

## 5. Error handling

| Scenario | Where it's handled today | Recommended change |
|---|---|---|
| Connection/auth failure at **test time** | `testConnection` catches broadly, returns the message | Classify per above; persist to `connection_status`/`last_test_message` |
| Broker unavailable at **publish time** | `ProducerBulkEngine` catches and calls `changeStatusForLastJob(..., "Broker configuration wrong job %s fail ...")` | Keep — but this message conflates "bad topic format" and "broker down"; worth splitting once topic-format parsing (`KafkaTopicPartitionUtil`) and actual send failures are distinguishable, so the job-history message tells an operator which one it was |
| Topic unavailable | Handled proactively — `KafkaTemplateProvider.ensureTopicExists` auto-creates the topic on every `source_task_type` add/update and at startup, best-effort (logs, doesn't throw) | With per-tenant clusters, this must run against the **resolved** profile for that task type/tenant, not just "whichever cluster is currently effective" — update the call site to pass the resolved `KafkaConnectionProfile` |
| Timeout | 10s bounded on admin calls (test/topic-check); producer send is currently fire-and-forget via `KafkaTemplate.send()` with no explicit timeout handling on the returned `ListenableFuture` | Add a `.addCallback`/exception handler on the send future so a timed-out or failed send updates job status instead of silently vanishing (worth checking whether `changeStatusForLastJob` is actually wired to the send future's failure path today — flag this as a thing to verify, not something I've confirmed either way) |
| Producer errors (serialization, etc.) | `RETRIES_CONFIG = 0` currently — **zero producer-level retries** | Recommend `retries` > 0 with `retry.backoff.ms` and `delivery.timeout.ms` set (standard Kafka producer resilience config) — this is a real production gap independent of this redesign, worth fixing regardless |
| Dead-letter | No DLQ concept exists today | Out of scope for this change, but worth a follow-up: a per-`source_task_type` `dlq_topic` (or reuse `additional_properties`) that `ProducerBulkEngine` publishes to after retries are exhausted, so failed messages aren't just a job-status row but a replayable artifact |
| Logging/monitoring | SLF4J structured logs throughout (`KafkaTemplateProvider`, `ProducerBulkEngine`) | This project already depends on `micrometer-registry-prometheus` (see `pom.xml`) but doesn't yet emit Kafka-specific metrics — recommend a `Counter`/`Timer` per `(tenantId, profileId, topic)` for publish success/failure/latency, exposed via the existing Actuator/Prometheus endpoint, no new infra needed |

---

## 6. API design

**Most of this already exists** (`KafkaConnectionProfileRestApi`) — the change is scoping it to
tenants and adding the routing-override surface, not building it from scratch.

```
# Existing — behavior changes, contract mostly stable
POST   /api/v1/kafkaConnectionProfile.json/addProfile           now implicitly scoped to caller's tenant
GET    /api/v1/kafkaConnectionProfile.json/fetchAllProfiles     TENANT_ADMIN sees own tenant + platform-wide (tenant_id NULL); PLATFORM_ADMIN sees all
PUT    /api/v1/kafkaConnectionProfile.json/updateProfile        + must reject cross-tenant target (IDOR check, same pattern as this session's SourceJob fixes)
PUT    /api/v1/kafkaConnectionProfile.json/deleteProfile        + block if referenced by source_task_type or tenant_task_type_kafka_route
POST   /api/v1/kafkaConnectionProfile.json/testConnection       unchanged
GET    /api/v1/kafkaConnectionProfile.json/testTopic            unchanged

# Renamed for clarity (activate/deactivate implied a single global switch; it's now per-tenant "default")
POST   /api/v1/kafkaConnectionProfile.json/setAsDefault?kafkaConnectionProfileId=      (was activateProfile)
POST   /api/v1/kafkaConnectionProfile.json/clearDefault                                (was deactivateActiveProfile)

# NEW — tenant routing override (source task type ↔ profile, per tenant)
GET    /api/v1/sourceTaskType.json/{id}/kafkaRoutes             list this tenant's override (0 or 1 row) for a task type
PUT    /api/v1/sourceTaskType.json/{id}/kafkaRoute               body: { kafkaConnectionProfileId }  -- create/replace the caller's tenant override
DELETE /api/v1/sourceTaskType.json/{id}/kafkaRoute               remove the override, fall back to the type's own default
```

**Changes to existing Source Task Type APIs** (`addSourceTaskType`/`updateSourceTaskType`):
accept an optional `kafkaConnectionProfileId` in `SourceTaskTypeDto`; validate (if a
`PLATFORM_ADMIN`/`TENANT_ADMIN` other than the profile's owner tries to reference a profile that
belongs to a *different* tenant, and isn't platform-wide, reject it) same way task/job linking
IDOR checks were added elsewhere this session.

---

## 7. Frontend design

### Remove

- `SchemaPayload` textarea + validation — `source-task-type.component.{ts,html}`.
- `schemaRegister` pill column — `setting.component.html`'s Source Task Type table.
- Both "Schema Payload" `<pre>` blocks — `source-task.component.html`, `source-job.component.html`.

### Add

- **Kafka Connection Profile picker** on the Source Task Type add/edit form
  (`source-task-type.component.html`): a `<select>` populated from
  `KafkaConnectionProfileService.fetchAllProfiles()` (already exists), showing
  `profileName (environmentLabel)` + a small status pill (`connectionStatus`). Optional —
  leaving it unset means "inherit the tenant's default," so this isn't a required field.
- A small "Manage Kafka Profiles →" link next to that picker, routing to the existing
  `kafka-connection-profile` screen (no new screen needed — see below).
- On the existing Kafka Connection Profile screen: an `environmentLabel` text input, a
  conditionally-shown SSL section (keystore/truststore location + password fields, masked,
  shown when `securityProtocol` is `SSL` or `SASL_SSL` — mirrors the existing `isSaslProtocol()`
  conditional field-set pattern already in `kafka-connection-profile.component.ts`), and a
  `connectionStatus` pill + `lastTestedAt` timestamp in the profile list table (the "Test"
  button already exists — it just needs to persist and display its own result instead of only
  toasting it).

### Do **not** add

A whole new "Kafka Connection Profile management screen" — **it already exists**
(`kafka-connection-profile.component.{ts,html}`, with create/edit/delete/test/activate all
wired up). Per your own instruction not to change things without a clear benefit: this screen
needs its data scoped to tenants and a few new fields, not a rebuild.

### Renamed

- "Activate"/"Deactivate" buttons → "Set as Default"/"Clear Default", matching the backend
  rename above (the old wording implied a single global switch, which is no longer the model).

### Sensitive field handling

Unchanged pattern, extended to the new fields: `saslPassword` is already never returned by the
API (only `saslPasswordConfigured: boolean`) and the form field is always blank on open, only
sent when the user actually types a new value. Apply identically to
`sslKeystorePassword`/`sslKeyPassword`/`sslTruststorePassword` (each gets its own `*Configured`
boolean; the form shows a "configured — leave blank to keep" placeholder rather than the value).

### Validation

Extend the existing conditional-validator pattern
(`isSaslProtocol(securityProtocol) → require saslMechanism + saslUsername`) with an
`isSslProtocol(securityProtocol) → require ssl_keystore_location + ssl_truststore_location`
check, same shape, same place (`kafka-connection-profile.model.ts` already exports
`isSaslProtocol` for exactly this).

### API integration changes

`KafkaConnectionProfile` frontend model gains: `tenantId?`, `environmentLabel?`, `isDefault?`
(replaces `connectionActive?`), `sslKeystoreLocation?`, `sslKeystorePasswordConfigured?`,
`sslKeyPasswordConfigured?`, `sslTruststoreLocation?`, `sslTruststorePasswordConfigured?`,
`connectionStatus?`, `lastTestedAt?`, `lastTestMessage?`. `SourceTaskType`
(`_models/object.ts`) loses `schemaRegister?`/`schemaPayload?`, gains
`kafkaConnectionProfileId?` and a read-only `kafkaConnectionProfileName?` for display.

### UX considerations

- A `TENANT_ADMIN` should only ever see/select their own tenant's profiles plus platform-wide
  shared ones — never another tenant's (same visibility rule as every other tenant-scoped list
  in this app now).
- The Source Task Type form's profile picker should show "(tenant default)" as the placeholder
  option, not a blank/`--select--`, so it's clear that leaving it unset is a deliberate,
  meaningful choice, not an omission.
- Surface `connectionStatus=FAILED` prominently (red pill) wherever a profile is picked from a
  dropdown elsewhere in the app, so a broken cluster is visible before a job actually fails
  against it.

---

## 8. Migration and backward compatibility

**Expand → migrate → contract**, in that order — the standard safe sequence for a schema change
that both adds and removes columns:

1. **DB migration (additive only)** — every new column above is nullable or has a safe default;
   run this first, before any code deploy. Zero behavior change: existing rows, existing
   queries, existing producer resolution all keep working exactly as today (steps 3-5 of the
   resolution order are the only rows that exist yet).
2. **Backend deploy** — ships `KafkaConnectionResolver` + the widened `KafkaTemplateRegistry`,
   still falling all the way through to today's env-var fallback for every existing tenant/task
   type until someone actually configures a profile/route. **Also** ships the code removal of
   `schemaPayload`/`isSchemaRegister` reads — DTOs/entities updated, but the DB columns still
   exist (harmless, unused) at this point.
3. **Frontend deploy** — new fields on the two forms; old cached frontend bundles hitting the
   new backend keep working unchanged (Jackson ignores unknown fields both directions, and every
   new backend field is optional).
4. **Data backfill (optional, your call)** — if you want existing tenants to start on their own
   cluster immediately rather than the shared platform default, insert their
   `kafka_connection_profile` rows and `tenant_task_type_kafka_route` overrides now. Not required
   for correctness — the resolution chain's step 4/5 covers anyone who hasn't been migrated yet.
5. **DB migration (destructive)** — `DROP COLUMN schema_payload, is_schema_register` — **only
   after step 2 is deployed and confirmed** (no code left referencing them). Take a
   `pg_dump --table=source_task_type` immediately before this step specifically.

**Rollback:** steps 1-4 are all trivially reversible (unused nullable columns, or just redeploy
older backend/frontend builds — nothing in them is destructive). Step 5 is the one point of no
return; sequencing it last, and only after the rest has run in production for a while, means a
rollback need is caught long before you reach the irreversible step. If you want extra safety,
hold step 5 back a full release cycle after step 2 ships.

**Existing clients/tasks:** nothing breaks at any point — the whole design is built around the
current single-global-cluster behavior being resolution step 5, the guaranteed final fallback,
not something being removed.
