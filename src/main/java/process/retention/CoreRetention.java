package process.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import process.pipeline.DatasetStore;
import process.util.OpenSearchAuditLogClient;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MIG-354: the classes Core holds itself -- run outputs, run logs, form submissions and the audit records -- each a
 * {@link RetentionTarget} whose preview and batches read the same rows (one WHERE per class, written once below).
 *
 * <p>Every statement runs as the workspace (the pass calls inside RowSecurity.forTenant) and names the workspace too.
 * A batch is at most the pass's limit of items, read oldest first over an index (job_queue's partial indexes, V212.1;
 * form_submission's (tenant_id, submitted_at); the audit tables' (tenant_id, time)), so no batch walks a workspace's
 * history: memory and time stay the batch's.
 *
 * <ul>
 *   <li><b>Run outputs</b> (a run whose outputs are still there, made before the cutoff, not in flight, not held): the
 *   run's folder of datasets and files (datasets/{run}/), its run_dataset rows (each written to retention_log, reason
 *   'policy'), its result records (and their reviews), its AI answers (run_ai_step). The manifest (run_output) stays and
 *   says the files expired then, so the run page and GET /v1/files answer "expired". job_queue.outputs_removed_at.</li>
 *   <li><b>Run logs</b>: the run's log lines -- OpenSearch's copy first, then job_audit_logs -- and its steps' logs
 *   (step_log). The run and its steps stay. job_queue.logs_removed_at.</li>
 *   <li><b>Form submissions</b> (submitted before the cutoff, outside a held pipeline or run): the submission's JSON copy
 *   and its files in the workspace's inbox (storage-service removes them, metered as the workspace's churn), then its
 *   form_upload and form_submission rows.</li>
 *   <li><b>Audit records</b>: file_access_log, retention_log, retention_audit and finished retention passes older than
 *   the cutoff -- which the pass never sets under the platform's floor.</li>
 * </ul>
 * Files go before rows: a batch whose files cannot be removed removes no row, and the next pass tries again; a removed
 * file whose row survived (the database failed after) is removed again harmlessly.
 */
@Component
public class CoreRetention {

    private static final Logger logger = LoggerFactory.getLogger(CoreRetention.class);

    /** Runs a pass never touches: still going. */
    static final String NOT_IN_FLIGHT = "UPPER(q.job_status) NOT IN ('QUEUE', 'START', 'RUNNING')";

    static final String RUN_OUTPUTS_WHERE = "q.tenant_id = ? AND q.outputs_removed_at IS NULL AND q.date_created < ? AND " + NOT_IN_FLIGHT
        + " AND COALESCE(q.job_id, -1) <> ALL (?) AND q.job_queue_id <> ALL (?)";
    static final String RUN_LOGS_WHERE = "q.tenant_id = ? AND q.logs_removed_at IS NULL AND q.date_created < ? AND " + NOT_IN_FLIGHT
        + " AND COALESCE(q.job_id, -1) <> ALL (?) AND q.job_queue_id <> ALL (?)";
    static final String FORMS_WHERE = "f.tenant_id = ? AND f.submitted_at < ? AND COALESCE(f.job_id, -1) <> ALL (?) "
        + "AND COALESCE(f.job_queue_id, -1) <> ALL (?)";

    /** The bytes of the files a run's outputs still hold (the manifest's own count, for datasets not yet expired). */
    static final String OUTPUT_BYTES = "SELECT COALESCE(sum(o.byte_count), 0) FROM run_output o JOIN step_execution s "
        + "ON s.step_execution_id = o.step_execution_id WHERE s.job_queue_id = ANY (?) AND o.run_dataset_id IS NOT NULL "
        + "AND EXISTS (SELECT 1 FROM run_dataset d WHERE d.run_dataset_id = o.run_dataset_id)";

    /** The audit class's tables: table, time column. */
    static final String[][] AUDIT_TABLES = {
        {"file_access_log", "accessed_at"},
        {"retention_log", "removed_at"},
        {"retention_audit", "at"},
        {"retention_pass", "started_at"},
    };

    private final JdbcTemplate jdbc;
    private final DatasetStore datasets;
    private final OpenSearchAuditLogClient logIndex;
    private final RemoteRetention remote;
    private final TransactionTemplate transaction;
    private final Clock clock;

    @Autowired
    public CoreRetention(JdbcTemplate jdbc, DatasetStore datasets, OpenSearchAuditLogClient logIndex, RemoteRetention remote,
        PlatformTransactionManager transactions) {
        this(jdbc, datasets, logIndex, remote, transactions, Clock.systemUTC());
    }

    public CoreRetention(JdbcTemplate jdbc, DatasetStore datasets, OpenSearchAuditLogClient logIndex, RemoteRetention remote,
        PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc;
        this.datasets = datasets;
        this.logIndex = logIndex;
        this.remote = remote;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** The class's target; Core's own classes only. */
    public RetentionTarget target(DataClass dataClass) {
        switch (dataClass) {
            case RUN_OUTPUTS:
                return this.runOutputs;
            case RUN_LOGS:
                return this.runLogs;
            case FORM_SUBMISSIONS:
                return this.forms;
            case AUDIT:
                return this.audit;
            default:
                throw new IllegalArgumentException(dataClass + " is not Core's.");
        }
    }

    // ------------------------------------------------------------------------------------------------------- run outputs

    private final RetentionTarget runOutputs = new RetentionTarget() {
        @Override
        public Tally preview(long tenantId, Instant cutoff, Holds holds) {
            Object[] args = args(tenantId, cutoff, holds);
            Map<String, Object> row = jdbc.queryForMap("SELECT count(*) AS n, min(q.date_created) AS oldest FROM job_queue q WHERE "
                + RUN_OUTPUTS_WHERE, args);
            long bytes = jdbc.queryForObject(OUTPUT_BYTES.replace("s.job_queue_id = ANY (?)", "s.job_queue_id IN (SELECT q.job_queue_id FROM "
                + "job_queue q WHERE " + RUN_OUTPUTS_WHERE + ")"), Long.class, args);
            long total = jdbc.queryForObject("SELECT count(*) FROM job_queue q WHERE q.tenant_id = ? AND q.outputs_removed_at IS NULL",
                Long.class, tenantId);
            return Tally.of(number(row.get("n")), bytes, instant(row.get("oldest")), total);
        }

        @Override
        public Batch purge(long tenantId, Instant cutoff, Holds holds, int limit, long passId) {
            List<Long> runs = jdbc.queryForList("SELECT q.job_queue_id FROM job_queue q WHERE " + RUN_OUTPUTS_WHERE
                + " ORDER BY q.date_created, q.job_queue_id LIMIT " + limit, Long.class, args(tenantId, cutoff, holds));
            if (runs.isEmpty()) {
                return Batch.of(0, 0, false);
            }
            BigintArray ids = new BigintArray(runs);
            long bytes = jdbc.queryForObject(OUTPUT_BYTES, Long.class, ids);
            for (Long run : runs) {
                try {
                    datasets.deleteRun(run);
                } catch (Exception failed) {
                    throw new IllegalStateException("Run " + run + "'s datasets could not be removed: " + failed.getMessage(), failed);
                }
            }
            Timestamp now = Timestamp.from(clock.instant());
            transaction.execute(status -> {
                jdbc.update("INSERT INTO retention_log (tenant_id, run_dataset_id, job_queue_id, step_execution_id, step_key, name, storage_key, "
                    + "row_count, expires_at, removed_at, reason) SELECT d.tenant_id, d.run_dataset_id, s.job_queue_id, s.step_execution_id, "
                    + "s.step_key, d.name, d.storage_key, d.row_count, d.expires_at, ?, 'policy' FROM run_dataset d JOIN step_execution s "
                    + "ON s.step_execution_id = d.step_execution_id WHERE s.job_queue_id = ANY (?) AND d.tenant_id = ?", now, ids, tenantId);
                jdbc.update("DELETE FROM run_dataset d USING step_execution s WHERE s.step_execution_id = d.step_execution_id "
                    + "AND s.job_queue_id = ANY (?) AND d.tenant_id = ?", ids, tenantId);
                jdbc.update("DELETE FROM result_record WHERE job_queue_id = ANY (?) AND tenant_id = ?", ids, tenantId);
                jdbc.update("DELETE FROM run_ai_step WHERE job_queue_id = ANY (?) AND tenant_id = ?", ids, tenantId);
                jdbc.update("UPDATE run_output o SET expires_at = ? FROM step_execution s WHERE s.step_execution_id = o.step_execution_id "
                    + "AND s.job_queue_id = ANY (?) AND o.tenant_id = ? AND (o.expires_at IS NULL OR o.expires_at > ?)", now, ids, tenantId, now);
                jdbc.update("UPDATE job_queue SET outputs_removed_at = ? WHERE job_queue_id = ANY (?) AND tenant_id = ?", now, ids, tenantId);
                return null;
            });
            return Batch.of(runs.size(), bytes, runs.size() == limit);
        }
    };

    // ---------------------------------------------------------------------------------------------------------- run logs

    private final RetentionTarget runLogs = new RetentionTarget() {
        @Override
        public Tally preview(long tenantId, Instant cutoff, Holds holds) {
            Map<String, Object> row = jdbc.queryForMap("SELECT count(*) AS n, min(q.date_created) AS oldest FROM job_queue q WHERE "
                + RUN_LOGS_WHERE, args(tenantId, cutoff, holds));
            long total = jdbc.queryForObject("SELECT count(*) FROM job_queue q WHERE q.tenant_id = ? AND q.logs_removed_at IS NULL",
                Long.class, tenantId);
            return Tally.of(number(row.get("n")), null, instant(row.get("oldest")), total);
        }

        @Override
        public Batch purge(long tenantId, Instant cutoff, Holds holds, int limit, long passId) {
            List<Long> runs = jdbc.queryForList("SELECT q.job_queue_id FROM job_queue q WHERE " + RUN_LOGS_WHERE
                + " ORDER BY q.date_created, q.job_queue_id LIMIT " + limit, Long.class, args(tenantId, cutoff, holds));
            if (runs.isEmpty()) {
                return Batch.of(0, 0, false);
            }
            BigintArray ids = new BigintArray(runs);
            long indexed = logIndex.deleteByJobQueueIds(runs);
            Timestamp now = Timestamp.from(clock.instant());
            transaction.execute(status -> {
                jdbc.update("DELETE FROM job_audit_logs WHERE job_queue_id = ANY (?) AND tenant_id = ?", ids, tenantId);
                jdbc.update("DELETE FROM step_log l USING step_execution s WHERE s.step_execution_id = l.step_execution_id "
                    + "AND s.job_queue_id = ANY (?) AND l.tenant_id = ?", ids, tenantId);
                jdbc.update("UPDATE job_queue SET logs_removed_at = ? WHERE job_queue_id = ANY (?) AND tenant_id = ?", now, ids, tenantId);
                return null;
            });
            if (indexed > 0) {
                logger.debug("Workspace {}: {} indexed log line(s) of {} run(s) removed.", tenantId, indexed, runs.size());
            }
            return Batch.of(runs.size(), 0, runs.size() == limit);
        }
    };

    // -------------------------------------------------------------------------------------------------- form submissions

    private final RetentionTarget forms = new RetentionTarget() {
        @Override
        public Tally preview(long tenantId, Instant cutoff, Holds holds) {
            Object[] args = args(tenantId, cutoff, holds);
            Map<String, Object> row = jdbc.queryForMap("SELECT count(*) AS n, min(f.submitted_at) AS oldest FROM form_submission f WHERE "
                + FORMS_WHERE, args);
            long bytes = jdbc.queryForObject("SELECT COALESCE(sum(u.size_bytes), 0) FROM form_upload u WHERE u.submission_id IN "
                + "(SELECT f.submission_id FROM form_submission f WHERE " + FORMS_WHERE + ")", Long.class, args);
            long total = jdbc.queryForObject("SELECT count(*) FROM form_submission f WHERE f.tenant_id = ?", Long.class, tenantId);
            return Tally.of(number(row.get("n")), bytes, instant(row.get("oldest")), total);
        }

        @Override
        public Batch purge(long tenantId, Instant cutoff, Holds holds, int limit, long passId) throws Unavailable {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT f.submission_id, f.bucket, f.storage_key FROM form_submission f WHERE "
                + FORMS_WHERE + " ORDER BY f.submitted_at, f.submission_id LIMIT " + limit, args(tenantId, cutoff, holds));
            if (rows.isEmpty()) {
                return Batch.of(0, 0, false);
            }
            List<Long> submissions = new ArrayList<>();
            List<String[]> objects = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                submissions.add(number(row.get("submission_id")));
                addObject(objects, row.get("bucket"), row.get("storage_key"));
            }
            BigintArray ids = new BigintArray(submissions);
            long bytes = 0;
            for (Map<String, Object> upload : jdbc.queryForList("SELECT bucket, storage_key, size_bytes FROM form_upload WHERE submission_id = ANY (?) "
                + "AND tenant_id = ?", ids, tenantId)) {
                addObject(objects, upload.get("bucket"), upload.get("storage_key"));
                bytes += number(upload.get("size_bytes"));
            }
            remote.deleteFormObjects(tenantId, objects);
            transaction.execute(status -> {
                jdbc.update("DELETE FROM form_upload WHERE submission_id = ANY (?) AND tenant_id = ?", ids, tenantId);
                jdbc.update("DELETE FROM form_submission WHERE submission_id = ANY (?) AND tenant_id = ?", ids, tenantId);
                return null;
            });
            return Batch.of(rows.size(), bytes, rows.size() == limit);
        }
    };

    /** Only what Core put in the inbox for a form (intake/forms/...); storage checks it again. */
    static void addObject(List<String[]> objects, Object alias, Object key) {
        if (alias == null || key == null) {
            return;
        }
        String k = String.valueOf(key);
        if (k.startsWith("intake/forms/") && !k.contains("..")) {
            objects.add(new String[]{String.valueOf(alias), k});
        }
    }

    // ------------------------------------------------------------------------------------------------------------- audit

    private final RetentionTarget audit = new RetentionTarget() {
        @Override
        public Tally preview(long tenantId, Instant cutoff, Holds holds) {
            long count = 0;
            long total = 0;
            Instant oldest = null;
            Timestamp before = Timestamp.from(cutoff);
            for (String[] table : AUDIT_TABLES) {
                Map<String, Object> row = jdbc.queryForMap("SELECT count(*) AS n, min(" + table[1] + ") AS oldest FROM " + table[0]
                    + " WHERE tenant_id = ? AND " + table[1] + " < ?" + finished(table[0]), tenantId, before);
                count += number(row.get("n"));
                Instant first = instant(row.get("oldest"));
                if (first != null && (oldest == null || first.isBefore(oldest))) {
                    oldest = first;
                }
                total += jdbc.queryForObject("SELECT count(*) FROM " + table[0] + " WHERE tenant_id = ?", Long.class, tenantId);
            }
            return Tally.of(count, null, oldest, total);
        }

        @Override
        public Batch purge(long tenantId, Instant cutoff, Holds holds, int limit, long passId) {
            Timestamp before = Timestamp.from(cutoff);
            long deleted = 0;
            for (String[] table : AUDIT_TABLES) {
                int room = (int) (limit - deleted);
                if (room <= 0) {
                    break;
                }
                Integer gone = transaction.execute(status -> jdbc.update("DELETE FROM " + table[0] + " WHERE ctid IN (SELECT ctid FROM " + table[0]
                    + " WHERE tenant_id = ? AND " + table[1] + " < ?" + finished(table[0]) + " ORDER BY " + table[1] + " LIMIT " + room + ")",
                    tenantId, before));
                deleted += gone == null ? 0 : gone;
            }
            return Batch.of(deleted, 0, deleted == limit);
        }
    };

    /** A pass is an audit record once it has ended (the running one is never removed). */
    private static String finished(String table) {
        return "retention_pass".equals(table) ? " AND state <> 'RUNNING'" : "";
    }

    // ----------------------------------------------------------------------------------------------------------- helpers

    static Object[] args(long tenantId, Instant cutoff, Holds holds) {
        Holds held = holds == null ? Holds.NONE : holds;
        return new Object[]{tenantId, Timestamp.from(cutoff), new BigintArray(held.jobArray()), new BigintArray(held.runArray())};
    }

    static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    static Instant instant(Object value) {
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toInstant();
        }
        if (value instanceof java.time.OffsetDateTime) {
            return ((java.time.OffsetDateTime) value).toInstant();
        }
        return null;
    }
}
