package process.pipeline;

import org.barco.platform.tenancy.AcrossTenants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * The retention sweep: removes run datasets past their expiry (MIG-230) and records each one it removed (MIG-243,
 * retention_log). run_dataset.expires_at is set when a step writes its output -- the pipeline's datasetRetentionHours,
 * cut to the workspace's data policy for the pipeline's sensitivity ({@link RetentionPolicy}). Hourly, a bounded batch
 * at a time: the dataset in the {@link DatasetStore}, then its row, then its retention_log row. The step's own row keeps
 * its records in and out, and the run's manifest (run_output) keeps naming the file and when it expired, so the
 * timeline still says what the step did; only the rows themselves are gone.
 *
 * <b>Only what the step engine made.</b> A run_dataset row is the engine's (MIG-230/V183), and its key is under its own
 * run's datasets/{run}/ -- the query asks for exactly that, and the key is checked again before anything is deleted.
 * Nothing else -- a workspace's buckets, uploads a step made (run_output kind 'bucket'), any other table -- is touched.
 *
 * Switched off with the rest of the scheduled work when process.scheduling.enabled is false.
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class DatasetSweep {

    static final int BATCH = 500;

    private static final Logger logger = LoggerFactory.getLogger(DatasetSweep.class);

    private final JdbcTemplate jdbc;
    private final DatasetStore datasets;
    private final Clock clock;

    @Autowired
    public DatasetSweep(JdbcTemplate jdbc, DatasetStore datasets) {
        this(jdbc, datasets, Clock.systemUTC());
    }

    /** For tests: the clock "expired" is read against. */
    public DatasetSweep(JdbcTemplate jdbc, DatasetStore datasets, Clock clock) {
        this.jdbc = jdbc;
        this.datasets = datasets;
        this.clock = clock;
    }

    @Scheduled(initialDelay = 5 * 60 * 1000, fixedDelay = 60 * 60 * 1000)
    @AcrossTenants("expired run datasets of every workspace are removed")
    public int sweep() {
        Timestamp now = Timestamp.from(this.clock.instant());
        List<Map<String, Object>> expired = this.jdbc.queryForList("SELECT d.run_dataset_id, d.tenant_id, d.step_execution_id, d.name, "
            + "d.storage_key, d.row_count, d.expires_at, s.job_queue_id, s.step_key FROM run_dataset d "
            + "JOIN step_execution s ON s.step_execution_id = d.step_execution_id "
            + "WHERE d.expires_at < ? AND d.storage_key LIKE 'datasets/' || s.job_queue_id || '/%' "
            + "ORDER BY d.run_dataset_id LIMIT " + BATCH, now);
        int removed = 0;
        for (Map<String, Object> dataset : expired) {
            String key = (String) dataset.get("storage_key");
            Object run = dataset.get("job_queue_id");
            if (!madeByTheEngine(key, run == null ? null : ((Number) run).longValue())) {
                logger.warn("Run dataset {} is not the step engine's (key {}); the sweep leaves it alone.", dataset.get("run_dataset_id"), key);
                continue;
            }
            try {
                this.datasets.delete(key);
                if (this.jdbc.update("DELETE FROM run_dataset WHERE run_dataset_id = ?", dataset.get("run_dataset_id")) == 1) {
                    this.record(dataset, now);
                    removed++;
                }
            } catch (Exception ex) {
                logger.warn("Expired dataset {} could not be removed; tried again next hour: {}", dataset.get("run_dataset_id"),
                    ex.getMessage());
            }
        }
        if (removed > 0) {
            logger.info("Removed {} expired run dataset(s).", removed);
        }
        return removed;
    }

    /** A key the step engine writes for this run: datasets/{run}/..., a plain path. */
    static boolean madeByTheEngine(String key, Long jobQueueId) {
        return key != null && jobQueueId != null && key.startsWith("datasets/" + jobQueueId + "/") && !key.contains("..")
            && !key.contains("\\");
    }

    private void record(Map<String, Object> dataset, Timestamp now) {
        this.jdbc.update("INSERT INTO retention_log (tenant_id, run_dataset_id, job_queue_id, step_execution_id, step_key, name, storage_key, "
                + "row_count, expires_at, removed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", dataset.get("tenant_id"), dataset.get("run_dataset_id"),
            dataset.get("job_queue_id"), dataset.get("step_execution_id"), dataset.get("step_key"), dataset.get("name"), dataset.get("storage_key"),
            dataset.get("row_count"), dataset.get("expires_at"), now);
    }
}
