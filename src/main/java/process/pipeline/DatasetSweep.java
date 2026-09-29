package process.pipeline;

import org.barco.platform.tenancy.AcrossTenants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Removes run datasets past their expiry (MIG-230): run_dataset.expires_at is set from the definition's
 * datasetRetentionHours (default 24) when a step writes its output. Hourly, a bounded batch at a time: the dataset in
 * the {@link DatasetStore}, then its row. The step's own row keeps its records in and out, so the timeline still says
 * what the step did; only the rows themselves are gone.
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

    public DatasetSweep(JdbcTemplate jdbc, DatasetStore datasets) {
        this.jdbc = jdbc;
        this.datasets = datasets;
    }

    @Scheduled(initialDelay = 5 * 60 * 1000, fixedDelay = 60 * 60 * 1000)
    @AcrossTenants("expired run datasets of every workspace are removed")
    public int sweep() {
        List<Map<String, Object>> expired = this.jdbc.queryForList("SELECT run_dataset_id, storage_key FROM run_dataset "
            + "WHERE expires_at < now() ORDER BY run_dataset_id LIMIT " + BATCH);
        int removed = 0;
        for (Map<String, Object> dataset : expired) {
            String key = (String) dataset.get("storage_key");
            try {
                this.datasets.delete(key);
                removed += this.jdbc.update("DELETE FROM run_dataset WHERE run_dataset_id = ?", dataset.get("run_dataset_id"));
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
}
