# ETL Console — backend

**Running the platform:** every service, its settings and its secrets are started from [etl-platform](https://github.com/NABEEL-AHMED-JAMIL/etl-platform) (`../etl-platform`): `scripts/up.sh`, `scripts/deploy.sh <service>`, `scripts/verify.sh`. This repository's own `docker-compose.yml` still works for standalone development, but once the service runs from etl-platform its `.env` here no longer configures the running container -- change `etl-platform/config/` or `etl-platform/secrets/` instead.

The server behind the ETL Console ("Core"). It began as a Kafka-backed **scheduler engine**, and that is still its core: 47 REST controllers (15 of them `/internal`, 6 the customer API) cover pipelines and the step engine, schedules, runs and their review, forms, inbox triggers, reports and the customer API's runs, events and reads. Identity, storage, media and documents, and analytics have moved out to their own services (MIG-108, MIG-70, MIG-48, MIG-128), beside ai-, billing-, integration- and workflow-service; Core reaches them over HTTP and Kafka.

Its frontend is the Angular 22 console in [`../scheduler1`](../scheduler1) (`next-app`, served on :4400 by etl-platform). The older Angular 8 console was retired on 2026-09-24.

> **As of 2026-10-07** (branch `media-extraction`, platform-commons 1.16.0): the step engine streams big files and spills to disk (ADR-026 phase 1, MIG-344); Read API, Aggregate, Compute, Send Notification and Measure image gained the features listed under [Pipeline steps](#pipeline-steps-as-of-2026-10-07); the customer API's runs, pipelines, events, files, reviews and view links are served here ([The customer API in Core](#the-customer-api-in-core-mig-332333334335-adr-025)). Architecture records: `../etl-platform/docs/adr/ADR-026-big-data-step-engine.md`, `ADR-025-customer-api.md`.

> The planning and per-feature scope for the current phase is in [`../.ai/`](../.ai/). Start with [`../.ai/project.md`](../.ai/project.md), and see [`../.ai/discovery/backend.md`](../.ai/discovery/backend.md) for the full endpoint and service inventory.

## The scheduler

```
Process has 6 types of scheduler
1. Mint    (runs on a minute interval,  e.g. every 5 minutes)
2. Hr      (runs hourly,                e.g. every 1 hour)
3. Daily
4. Weekly
5. Monthly
6. Cron    (the schedule's own cron expression, scheduler.cron_expression, V187)
```

These are exactly the values of `process.model.enums.Frequency`.

### Architecture

1. **Producer** — sends job/task messages into Kafka topics
2. **Scheduler engine** — pulls due jobs, applies scheduling logic, dispatches them
3. **Consumer** — reads results and error events back off Kafka
4. **Step engine** — a pipeline with a step definition runs inside Core (`process.pipeline.StepEngine`, MIG-230; streaming per ADR-026, below). The engine takes the run and reports through the same callback a worker uses (`NotifyService.changeState`), so statuses, retries, mails and metering are unchanged. A pipeline without one (a "Legacy" task) is still published through `dispatch_outbox` to its worker's Kafka queue, but no worker is part of the platform any more: the Python workers (`../job-search`) were retired on 2026-09-24 and the step engine replaced them (the Java `service-1` worker is a test executor only).

Concurrency comes from Spring's own scheduling pool, sized by `spring.task.scheduling.pool.size`, and the step engine's own threads (`process.pipeline.engine.threads`, default 4). (Earlier versions of this README described a `ThreadPoolExecutor` with a `PriorityBlockingQueue`; no such code exists today.)

## Tech stack

| | |
|---|---|
| **Java 17 / Spring Boot 2.7.18** | Core engine. Java 17 bytecode, JDK 17 runtime (MIG-204) |
| **Apache Kafka** (`cp-kafka` 7.5.0 + ZooKeeper) | Messaging and stream processing |
| **PostgreSQL 15** | Required, not optional. Schema managed by **Liquibase** (`src/main/resources/db/changelog/`): the V50.0 baseline, then V51.0 → V204.0 as of 2026-10-07 (V1–V49 are kept under `archive/` and no longer run). Row-level security on every tenant table (V181, below) |
| **Redis** | Caching, token revocations shared with Identity, locks and one-time secrets |
| **storage-service** | Object storage (MinIO, S3, Azure Blob, per connection) is storage-service's; a pipeline's bucket steps go through its trusted contract (`TrustedBucketStore`). Document conversion left with media-service (MIG-48) |
| **DuckDB 1.1.3** | Reads Parquet for the step engine (`ParquetRows`) |
| **Spring Security + JWT** | Tokens from identity-service (RS256 on the platform, checked against its JWKS). Three roles: `PLATFORM_ADMIN` > `TENANT_ADMIN` > `TENANT_USER` |

`docker-compose.yml` additionally runs `kafka_ui` and `redisinsight`, and LocalStack (`--profile aws`) and Azurite (`--profile storage-test`) on request.

## Running

### Option 1 — local

```bash
git clone https://github.com/NABEEL-AHMED-JAMIL/process.git
cd process
mvn clean package -DskipTests
java -jar target/*.jar
```

Work happens on `media-extraction`. etl-platform builds Core from `../process-main`, a worktree of that branch (`config/process_app/SOURCE`); `../process` is the owner's own checkout and is never built from.

### Option 2 — Docker Compose (recommended)

```bash
# Build the JAR first — the Dockerfile COPYs a prebuilt jar rather than building one
mvn clean package -DskipTests

# Start PostgreSQL, ZooKeeper, Kafka, Redis, Kafka UI, RedisInsight and the app
docker-compose up -d

docker-compose logs -f
docker-compose down
```

> **The Dockerfile copies a prebuilt jar.** `mvn test` does not write one, so a build run after only testing ships whatever jar was there before. If you are deploying a change, verify what actually shipped — compare the checksum of the jar inside the image against `target/`. This has silently shipped stale code more than once.

| | URL |
|---|---|
| API | `http://localhost:9098/api/v1` |
| Swagger UI | `http://localhost:9098/api/v1/swagger-ui.html` (a platform administrator only) |
| Health | `http://localhost:9098/api/v1/actuator/health` |

Core listens on 9098 inside its container. Under etl-platform (and this compose file) the host's 9098 is the API gateway's, and Core itself is on `127.0.0.1:9099`.

Schema migration is automatic — Liquibase runs on startup, and `ModelApplication` seeds `SCHEDULER_LAST_RUN_TIME` if it is absent without overwriting an existing value. **No manual bootstrap SQL is needed;** the statements older versions of this README carried no longer match the tables.

## Bulk template endpoints

```
GET  /api/v1/sourceJob.json/downloadSourceJobTemplateFile
POST /api/v1/sourceJob.json/uploadSourceJob
GET  /api/v1/sourceTask.json/downloadSourceTaskTemplate
POST /api/v1/sourceTask.json/uploadSourceTask
```

(These replace the `bulk.json` endpoints named in earlier versions of this file; that controller no longer exists.)

## Tests

| Suite | Command | Count |
|---|---|---|
| Unit and Postgres | `mvn -o test` | about 2,661 as of 2026-10-07 (the 91 `*PostgresTest` classes need NOTIFICATIONS_TEST_DB_*) |
| End-to-end, over real HTTP | `./run-e2e.sh` (`*E2EIT`, `HarnessSmokeIT`) | 13 |
| Kafka security matrix, real broker | `./run-kafka-matrix.sh` | 17 |

`run-e2e.sh` reads the database credentials and the encryption key from the running `process_app` container, so the stack must be up. `run-kafka-matrix.sh` **must** run in a container — every listener on the test broker is advertised as `host.docker.internal`, which the host cannot resolve, and a host run fails with metadata timeouts that look nothing like the cause. Bring that broker up with `kafka-it/start.sh`.

## Row-level security (MIG-258)

Every table of etl_job with a `tenant_id` has Postgres row-level security (V181), as a second guard behind the tenant
filter, `TenantScope` and the scoped queries: a query that forgets its tenant answers the caller's workspace and
nothing else.

- **Who the database sees.** Core connects as a superuser, which row security never applies to, so every pooled
  connection says `SET ROLE process_app` (`spring.datasource.hikari.connection-init-sql`, from
  `PROCESS_DB_SESSION_SQL`); Liquibase keeps the login on a connection of its own. `process_app` has no login, owns
  nothing, and holds only row grants. `PROCESS_DB_SESSION_SQL=SELECT 1` is the way back without a rebuild.
- **What each statement runs as** (platform-commons' `RowSecurityDataSource`, wired by `config/RowSecurityConfig`):
  the token's workspace; every workspace for a platform administrator or inside an across-tenants grant; the run's
  workspace for a worker's callback (`RowSecurity.forTenant`); nothing at all otherwise. A thread nobody set -- a
  cron, a relay, a listener, an `/internal` call -- sees no tenant rows and can write none until the code says why
  it may: `@AcrossTenants("why")` or `RowSecurity.acrossTenants("why", ...)`. `RowSecurityContractTest` lists every
  one, with its reason.
- **Platform rows.** `kafka_connection_profile`, `source_task_type`, `storage_connection`, `lookup_data` and
  `user_directory` keep rows with `tenant_id` NULL that every workspace reads (and none may write); elsewhere a NULL
  tenant is the platform's alone.
- **Tests.** `CoreRowSecurityPostgresTest` (every tenant table guarded; a raw query as `process_app` sees nothing
  without a workspace, only A's with A; writes into B refused; the parent-tenant trigger reads as the session;
  `process_app`'s grants). The five `CoreCrossTenantProbe*PostgresTest` classes run every probed endpoint on
  `ScratchPostgres.appPool()`, i.e. under row security. The system paths' own Postgres tests (enqueuer, dispatcher,
  pre-dispatch, relay, orphan audit, reconciliation, Identity listener, write-back, SLO) run their code as
  `process_app` behind `AcrossTenantsProxy`, nobody signed in; `WorkerCallbackRowSecurityPostgresTest` does the same for
  the worker callbacks; `RowSecurityContextPostgresTest` wires the hooks in a Spring context; `RowSecurityChangesetPostgresTest`
  rolls V181 back and applies it again.
- **Performance.** `DashboardIndexPostgresTest` checks the per-day Dashboard reads still use the day index as
  `process_app`: row security evaluates its policy before any condition that is not LEAKPROOF, so V181 marks
  `timezone(text, timestamptz)` and `date(timestamp)` LEAKPROOF in etl_job -- re-run those two lines after any
  pg_dump/restore (pg_catalog is not dumped). `DispatchClaimUnderRowSecurityPostgresTest`: the dispatcher's claim plans
  on `idx_scheduler_due` under row security, 20,000 schedules over 40 workspaces.
- **Trigger functions.** The four that keep a derived column (V84's dispatch_eligible, V85's assigned_username) run as
  their owner (SECURITY DEFINER); `tenant_id_from_parent` stays the writer's, so a child filed under a parent the
  session cannot see fails.

## The big-data step engine (ADR-026 phase 1, MIG-344)

Deployed 2026-10-07. A step pipeline's steps no longer hold their whole input in memory when they can stream.

- **Row file.** A step's output dataset is Core's own row file (`.rows`, `RowsFile`): about 1.3x the CSV's size, read back as exactly the values the JSON store gave (same keys, order and types; `RowsFileTest`). Old runs' `.json` datasets still read and download as before.
- **Pull-based streaming.** A task that implements `StreamingStepTask` reads its input as a `RowSource` in batches of `process.pipeline.batch-rows` (1,024) and writes a `RowSink`; it reads the next batch only after writing the last, so a step holds one batch however big the file is. Each step keeps its own `step_execution`, tries, timeout and downloadable output; an output is a partial file until the try succeeds. Phase 1 streams read_file (CSV, JSON Lines), validate (500-row batches to integration-service), compute, filter, select, save_file, upload_bucket and aggregate.
- **Aggregate spills to disk.** Past the run's budget, aggregate re-reads its input into 8 to 256 hash partitions (up to three levels) under `<datasets dir>/scratch/<run>/<attempt>/<step>/`, removed when the try ends. The output is the same groups, order and values as in memory (`AggregateSpillTest`), `list` included.
- **Per-run memory budget.** `PROCESS_PIPELINE_RUN_MEMORY_MB` (`process.pipeline.run-memory-mb`, default 128) bounds what a run's rows take at once; keep `engine.threads` x budget at or under half the heap (4 x 128 MB of 1.2 GB). A step that cannot fit fails and names the setting. A big or spilled step's log ends with the batch size, the memory held and the bytes spilled.
- **Steps that do not stream yet** get their input whole, held to `Limits` (50,000 rows, 1,000,000 cells); an input a streaming step made bigger fails that step with the fix ("Put a filter, select or aggregate before it."). Read API and Send Notification read only a streamed input's size and first row (`StepContext.inputSize()`, `firstInputRow()`), so they follow a big step.
- **Native memory.** etl-platform's `config/process_app/settings.env` sets `MALLOC_ARENA_MAX=2` (glibc arenas had taken about 860 MB outside the heap on a 1M-row run) and `-Djdk.nio.maxCachedBufferSize` in `JAVA_TOOL_OPTIONS`. `FileDatasetStore.readFile` reads datasets in 64 KB chunks (c7e913a), so a large download no longer leaves a file-sized direct buffer cached on a request thread.
- **Measured live:** 1M rows (86 MB) in 16.2 s at a 1,356 MB RSS peak; 10M rows (874 MB) in 144.5 s at 1,310 MB; `storage.bytes.read` equal to the object's size. Bench: `BigDataBench`; regression: etl-platform `scripts/load/load.py --groups bigfile`.
- `Values.number` and `Expression` now check a text against the number grammar before parsing, instead of catching an exception per cell (aggregate 5-10x faster; a 50,000-row text filter 54 ms -> 1-4 ms; `ValuesNumberTest`, `ExpressionNumberGuardTest`).

Not yet (phase 2): streaming transform, enrich and join; tenant-scoped dataset keys and the shared file area; per-step memory columns; admission by budget; disk quotas.

## Pipeline steps (as of 2026-10-07)

All generic: no knowledge of any customer's data shape in code.

- **Read API.** `rowsPath` fans out with `[]` or `[*]` (`entry[].resource`); optional `fields` (path/target, as in Enrich) make named columns from nested answers (`code.coding[0].code`). One field path may fan out over one list (`reaction[].pt`): a row per value, the other columns repeated, an empty list one row with that column empty; two different lists are refused at save time (`FieldPaths`, shared with Enrich, which fans out per input row). Request variables take `{{column}}` from the first row of the step's input, under the run's own placeholders. The request runs through integration-service, so its paging is the request's own, including `NEXT_URL` and `OFFSET`.
- **Placeholders.** A run started for a file (inbox arrival, form submission, API run) has `{{input_key}}` (the file's key in the workspace's storage) and `{{input_name}}` (its last part) wherever placeholders are filled; read_s3's prefix then lists exactly that object (`Templates`).
- **Aggregate** op `list`: a group's distinct non-empty values as one text in first-seen order, joined by ", ", at most 50 named and the rest counted ("+3 more").
- **Compute formulas:** `today()` (the business date, America/Chicago, as `{{date}}`) and `regex_extract(text, pattern)` (the first group of the first match, else the whole match, null when none) (`Expression`).
- **Send Notification:** the first row's columns as `{{column}}` in the title and message (`{{run}}` stays the run).
- **Measure image** is generic (`MeasureImageStepTask`): a `target` says what to measure -- `contrast` (default for a new step: the largest region outside the ruler that differs markedly from the photo's border) or `red_region` (a red region on a lighter background). `red_on_skin`, the earlier name, is still read and measures exactly as before; a save writes `red_region`.
- **Run with a different model** now covers step-engine AI steps (b6a284b): `StepReferences` reads which steps name a prompt or an API request from the Task Registry's schema formats; `AiModelChoiceService` lists those AI steps, and the ai_prompt step sends the run's model (Run with..., else the schedule's) to ai-service. Prompts' and API collections' Used by count step-engine pipelines (`PipelineUsage`; `POST /internal/pipelines/apiRequestUsers` for integration-service).
- **Schedule preview** is `GET /api/v1/sourceJob.json/schedulePreview` (the timetable in the query): an unsaved timetable's next runs by the scheduler's own rules, a read, so a managed-service session's audit no longer records every preview.

## The customer API in Core (MIG-332/333/334/335, ADR-025)

The gateway rewrites `/v1/x` to `/api/v1/customer/x` on the owning service; under `/customer/` Core accepts only an API client's token (`type: client`), checked again here with its scope. Every read is in the client's workspace under row-level security; another workspace's id is a 404.

| Path (as the customer calls it) | Core's controller | |
|---|---|---|
| `GET /v1/pipelines`, `/v1/pipelines/{id}`, `POST /v1/pipelines/{id}/runs` | `CustomerPipelinesRestApi` | list, read with the input contract, start a run (one in flight per pipeline, else 409 `/problems/run-in-flight`); the intake is one JSON file in the workspace's inbox |
| `POST /v1/events` | `CustomerEventsRestApi` | an event in; answers `{eventId, started, workflows, notStarted}` |
| `GET /v1/runs`, `/{id}`, `/{id}/steps`, `/{id}/outputs` | `CustomerRunsRestApi` | runs however started, filtered and keyset-paged; the latest attempt's steps; the manifest of made and given files |
| `GET`, `POST /v1/runs/{id}/review` | `CustomerRunsRestApi` | the customer's review through `RunReviewService.decide` (the console's rules); a rejection with `rerun` starts an API run again |
| `POST /v1/runs/{id}/view-links` | `CustomerRunsRestApi` | a signed 15-minute link to the console's read-only `/embed/runs/{token}` (`customer.embed.console-url`) |
| `GET /v1/embed/runs/{token}`, `/frame` | `CustomerEmbedRestApi` | the view's signed read and its frame check (the client's frame allow-list is Identity's) |
| `GET /v1/files/{id}`, `/meta`, `/content?token=` | `CustomerFilesRestApi`, `CustomerFileLinkRestApi` | a 302 to a 5-minute signed link on the API itself, never a bucket URL; every read logged in `file_access_log` |

Creating POSTs take an `Idempotency-Key` (receipts per workspace, client and key). **Events out:** V203's `api_event_out` journal is written by triggers in the transaction of the change (a run Running/Completed/Failed, a made file, a settled form submission) and by every review decision; `CustomerEventRelay` (ShedLock, every second) relays it through `platform_outbox` to `platform.customer.events.v1`, once and in order per workspace. integration-service turns those into signed webhooks. A pipeline's `emits` names `file.available` when a step makes a file. Checked live by etl-platform's `tests/customer-api/*_check.py`.

## Monitoring

Spring Boot Actuator is enabled, deliberately narrowed to:

```
management.endpoints.web.exposure.include=health,info,metrics,prometheus
management.endpoint.shutdown.enabled=false
```

The rest — `env`, `configprops`, `heapdump`, `threaddump`, `beans` and the others — are **off on purpose**: they leak configuration and secrets. `prometheus` is there for Grafana.

## Diagrams

| | |
|---|---|
| Old ETL workflow | ![old ETL workflow](ext-detail/old-etl.png) |
| New ETL workflow | ![new ETL workflow](ext-detail/new-etl.png) |
| Kafka topic structure | ![topic detail](ext-detail/Topic-Detail.png) |
| Database UML | ![database design](ext-detail/new-dbdesing.png) |

## Note on the wider documentation

Eight further markdown files exist under `ext-detail/md/` (seven) and `docs/design/` (one) that are not linked from here and have not been verified. Treat them as unknown. The superseded content of this README — the bootstrap SQL and the actuator endpoint dump — is preserved in [`../.ai/old-scope/`](../.ai/old-scope/).
