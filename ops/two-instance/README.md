# Two-instance harness (MIG-132)

The exit criterion for wave zero: process run as **two instances, A and B, from one image, against one
database, one Redis and one Kafka, behind a round-robin balancer with no sticky sessions**, and the ten
assertions of `16-testing-strategy.md` §8.1 checked against it. Mechanisms that left process are checked
against the service that owns them now, with two replicas of *that* service: T2/T3 notifications-service,
T8 analytics-service, T10 billing-service.

```
ops/two-instance/run.sh                                  # build process, unit gate, T1-T10
ops/two-instance/run.sh --only T1,T4,T5                  # some checks
ops/two-instance/run.sh --no-build --mutate split-redis  # a mechanism broken on purpose: the named checks must go red
ops/two-instance/run.sh --keep                           # leave it up; then:
python3 ops/two-instance/harness.py --attach --only T4   #   re-run checks against it
python3 ops/two-instance/harness.py --down --reuse       #   and take it down
python3 ops/two-instance/harness.py --full-suite         # process's whole mvn -o test, opt-in Postgres suites included
```

One line per check, `T<n> PASS|FAIL <evidence>`, then a table. Exit status 0 only when every selected
check passed (with `--mutate`: only when every expected check went red). A full run takes about 15 minutes,
most of it waiting for scheduled work to tick. One harness at a time: every invocation (except `--attach`)
starts by taking the previous one down.

## What it starts

`compose.yml`, project `mig132`, network `mig132_harness`, containers `mig132-*`:

| Container | What |
|---|---|
| `mig132-postgres` | Postgres 15 on tmpfs: every run starts empty. `etl_job` is built by process's own Liquibase as A and B boot; `notifications_db`, `analytics_db`, `billing_db` and their roles by `postgres/init.sh`. Loopback `:15432` for the opt-in Postgres suites. |
| `mig132-redis`, `mig132-kafka` | One Redis, one single-node Kafka (KRaft). `mig132-redis2` only for mutation runs. |
| `mig132-process-a`, `-b` | The image `process-two-instance:harness` (run.sh builds it from this checkout; never `process-process_app`). Loopback `:19101`, `:19102`. |
| `mig132-lb` | nginx round-robin, no `ip_hash`/cookie; alias `process` on the harness network; `X-Upstream` names the instance that answered. Loopback `:19198`. |
| `mig132-notifications-a`, `-b` | `NOTIFICATIONS_IMAGE` (default `notifications-service:local`). Loopback `:19111`, `:19112`. |
| `mig132-analytics-a`, `-b` | `ANALYTICS_IMAGE` (default `analytics-service:local`). |
| `mig132-billing-a`, `-b`, `mig132-meter-stub` | `BILLING_IMAGE` (default `billing-service:local`); a meter that has measured nothing. |

**Self-contained on purpose.** Nothing joins `process_default`, takes an alias the live gateway resolves,
consumes a live topic or publishes on the live Redis, so two extra notifications replicas cannot double
live pushes and a retention delete cannot touch live history. Every secret (database passwords, JWT key,
encryption key, service token) is generated per run into `.run/env` (git-ignored, 0600) and never printed;
nothing is read from any `.env`. Outbound calls process could make (storage, media, AWS, meter, OpenSearch)
point at unresolvable names.

**Seeded data, reset per run.** The database is new each run. Rows the checks write carry ids in the
9 132 xxx-9 136 xxx range, in tenant 9132001: three users (platform admin, tenant admin, tenant user), the
T5 throwaway user, 200 T1 jobs, the T7 stranded run, 42 analytics history rows.

## The checks

| # | How it is proved |
|---|---|
| unit gate | `mvn -o test` of the cross-instance and Postgres suites (ReconcileOncePerTickTest, DispatchBudgetInsideLockTest, DispatchTimingTest, PageAccessCache/LoginAttemptGuard/TokenRevocation AcrossInstances, EnqueuerReplicas/OneRunInFlight/DueSchedulerClaim/StalledRunSweep Postgres), the Postgres ones against the harness server. |
| LB | Six requests through the balancer alternate A, B, A, B... |
| T9 | A and B booted together on an empty database (their Liquibase runs race for the changelog lock), then stopped and booted together again with work due: both healthy, no restarts. |
| T1 | 200 schedules due at once, written while both were stopped, both started together so their enqueuers wake within a second of each other: every job has exactly one `job_queue` row; how many slots each instance took. |
| T5 | A throwaway user created for the purpose; five made-up wrong passwords A,B,A,B,A; the sixth (on B) and seventh (through the balancer) are refused as locked. No real account's credentials are ever used. |
| T4 | Page `reports` granted to the tenant user, B's cache warmed, the grant revoked through A: B refuses within the 2 s poll (asserted at 3.5 s; the 15 s TTL is the outage bound), and the reverse. |
| T6 | A configuration value added and then edited through A: B's `/setting.json/pipelineConfig` has it within 1.5 s. Since MIG-167 retired lookup_data (and its in-memory copy and shared cache version), the configuration store is read from the table on every request, so there is no cache to go stale. |
| T7 | A run stranded seven hours ago; B restarted so the replicas' start times differ; the sweep's ShedLock row is watched for 150 s: one execution per minute tick, on the tick, and the run closed once. `DISPATCH_BUDGET_MS < lockAtMostFor` is DispatchBudgetInsideLockTest in the unit gate. |
| T2 | A job event keyed onto a partition only notifications-B consumes; a browser (STOMP over WebSocket) on A receives it; B's `ws_broadcast_published` and A's `ws_broadcast_received` counters move. |
| T3 | The user's only session is on A; `ws:online:<user>` says online; a notice keyed onto B's partition is filed by B and sent with `convertAndSendToUser`; A's session receives it. |
| T8 | 37 expired and 5 recent analytics history rows; both analytics replicas started together with a 30-day window: one deletion count logged, of 37, across both. |
| T10 | Month close every two minutes on both billing replicas (production: the 1st at 08:00): one close per tick across both, on the tick. |

## Mutations

Each breaks one mechanism and names the checks that must go red; all but the first do it through
configuration alone, on the real images. `run.sh --no-build --mutate <name>` runs just those checks and
exits 0 only when they went red.

| Mutation | Breaks | Expect red |
|---|---|---|
| `unlocked-enqueuer` | the enqueuer's claim without `FOR UPDATE SKIP LOCKED`, and the V83 one-run-per-job index dropped (image from `mutants/build-unlocked-enqueuer.sh`) | T1 |
| `split-login-guard` | A and B count failed sign-ins under different keys (the per-JVM guard) | T5 |
| `split-redis` | process B on a Redis of its own: cache versions and counters per instance again | T4, T5 (T6 has no cache since MIG-167) |
| `unaligned-reconcile` | B sweeps every other minute, 40 s after A's tick: past A's lock, so both sweep in that minute (the fixedDelay defect) | T7 |
| `split-broadcast` | notifications B on a Redis of its own (the per-instance STOMP broker) | T2, T3 |
| `unaligned-close` | billing B closes 90 s after A's tick | T10 |

## CI

There is no CI in this repository yet. A job would need a runner with Docker (Compose v2), JDK 17, Maven and
Python 3, the sibling images built first, and then one command whose exit status is the gate:

```yaml
# e.g. .github/workflows/two-instance.yml, on every push to a release branch and nightly
- run: docker compose -f ../notifications-service/docker-compose.yml build   # and analytics-, billing-service
- run: python3 ops/two-instance/harness.py --full-suite
- run: ops/two-instance/run.sh
  env:
    NOTIFICATIONS_IMAGE: notifications-service:local
    ANALYTICS_IMAGE: analytics-service:local
    BILLING_IMAGE: billing-service:local
    LB_IMAGE: nginx:1.27-alpine
# nightly, after the above: every mutation must be caught
- run: ops/two-instance/mutants/build-unlocked-enqueuer.sh
- run: for m in unlocked-enqueuer split-login-guard split-redis unaligned-reconcile split-broadcast unaligned-close; do ops/two-instance/run.sh --no-build --no-unit --mutate "$m"; done
```

`LB_IMAGE` defaults to the first local image with nginx in it (`nginx:1.27-alpine`, then `scheduler1-app`,
`next-app`), so an offline machine does not pull one.
