# Worker callback tokens (per run)

How an ETL worker proves it is allowed to report on a run, since `platform-fixes-and-cleanup`
(Liquibase V42). Replaces the single shared `WORKER_CALLBACK_TOKEN` as the proof.

## Why

The callback endpoints (`/changeState`, `/addLogs`, `/addLogsBatch`) sit outside the JWT chain.
With one shared secret, every worker of every tenant carried the same standing credential: one
leaked env file could rewrite any tenant's run status and audit log, and rotating it meant
redeploying every worker at once. A secret per tenant or per user would only shrink the blast
radius; it would still be a long-lived credential sitting in worker environments.

## What happens now

| Step | Where | What |
|------|-------|------|
| Dispatch | `ProducerBulkEngine.getSourceJobDetail` → `RunCallbackTokens.issue` | Mints `cbt_<attempt>.<jobQueueId>.<32 random bytes, base64url>`; stores **only the SHA-256 hash**, the attempt and an expiry (`now + worker.callback.budget-hours`) on the `job_queue` row; puts the token in the Kafka message as `callbackToken` (plus `attempt`). Saved before the send, so a fast worker can never call back before the server knows the token. |
| Callback | `NotifyResetApi.rejectIfUntrusted` → `RunCallbackTokens.verify` | Looks the run up by `jobQueueId`; refuses if the job id does not match, no token was ever issued, the token expired, or the hash does not match (constant-time). Every refusal is the same `401 {"status":"ERROR","message":"Unauthorized worker callback."}`; the reason is only logged. |
| Retry | dispatch again | The row is re-minted: the earlier attempt's token stops working. |
| End of run | `changeState` to `Failed`/`Completed` accepted by the state machine | `RunCallbackTokens.retire` clears the hash and expiry. A replayed callback then finds nothing to match. A refused transition (e.g. `Start → Completed`) leaves the token in place. |

The `cbt_…` prefix and ids are for reading a log line; nothing trusts them — the random part is
what is hashed and compared.

## Worker contract

Read `callbackToken` from the run message and send it back **unchanged** as the
`X-Worker-Token` header on every callback for that run:

```
POST /api/v1/addLogs/jobId/{jobId}/jobQueueId/{jobQueueId}
POST /api/v1/addLogsBatch/jobId/{jobId}/jobQueueId/{jobQueueId}       {"messages":[...]}
POST /api/v1/changeState/jobId/{jobId}/jobQueueId/{jobQueueId}/jobStatus/{Running|Failed|Completed}
X-Worker-Token: cbt_1.5710.w6k-…
```

Report `Running` before `Failed`/`Completed`; the state machine refuses `Start → Completed`.
Do not persist the token anywhere beyond the life of the run; there is nothing to configure in
the worker's environment.

## Configuration

| Property / env | Default | Meaning |
|---|---|---|
| `worker.callback.budget-hours` / `WORKER_CALLBACK_BUDGET_HOURS` | 24 | How long a token stays good after dispatch, whatever the run does. A backstop for a run that never reports, not a run timeout. |
| `worker.callback.token` / `WORKER_CALLBACK_TOKEN` | *(empty)* | **Legacy.** Honoured only for a run whose `job_queue` row carries no hash (dispatched before V42) and only while set. It never opens a run that has a token. Remove it once every worker echoes the run token and that path closes on its own. |

The application no longer refuses to start without `WORKER_CALLBACK_TOKEN`.

## Schema (V42)

`job_queue.callback_token_hash VARCHAR(64)`, `callback_token_attempt INTEGER`,
`callback_token_expires_at TIMESTAMP` — all nullable. Redis and OpenSearch are untouched.

## Verified on 2026-09-17 (MedAxis, job 2422)

| Call | Result |
|---|---|
| no header / wrong token / legacy secret on a tokened run / right token with the wrong `jobId` | 401 |
| right token: `addLogs`, `addLogsBatch`, `Running`, `Completed` | 200; hash cleared after `Completed` |
| same token replayed after `Completed` | 401 |
| run with no hash + wrong secret | 401 |
| run with no hash + legacy secret | 200 (fallback) |

Tests: `RunCallbackTokensTest` (8), `NotifyResetApiTest` (8).
