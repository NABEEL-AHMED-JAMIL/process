package process.customer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import process.pipeline.PipelineDefinition;
import process.pipeline.PipelineDefinitionStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the customer API calls a pipeline (MIG-332, ADR-025 decision 3): a job of the workspace -- what Run now starts --
 * with the step pipeline its task names, when it has one. Its id is the job's, written as a string (ids are opaque); a
 * deleted job is not there. Read as the caller (row security), never across workspaces.
 */
@Component
public class PipelineCatalogue {

    /** One pipeline as the API knows it. */
    public static final class Entry {
        public final long jobId;
        public final long tenantId;
        public final String name;
        public final String status;
        public final String runningStatus;
        public final String pipelineId;
        /** The step pipeline's latest definition; null for a job without one. */
        public final PipelineDefinition definition;

        Entry(long jobId, long tenantId, String name, String status, String runningStatus, String pipelineId, PipelineDefinition definition) {
            this.jobId = jobId;
            this.tenantId = tenantId;
            this.name = name;
            this.status = status;
            this.runningStatus = runningStatus;
            this.pipelineId = pipelineId;
            this.definition = definition;
        }

        /** The data contract a started run's record must meet: settings.inputContract, else the first validate step's. */
        public Optional<PipelineDefinition.ContractRef> inputContract() {
            if (this.definition == null) {
                return Optional.empty();
            }
            PipelineDefinition.ContractRef declared = this.definition.getSettings() == null ? null
                : this.definition.getSettings().getInputContract();
            if (declared != null) {
                return Optional.of(declared);
            }
            for (PipelineDefinition.Step step : this.definition.getSteps() == null
                ? Collections.<PipelineDefinition.Step>emptyList() : this.definition.getSteps()) {
                if (step != null && "validate".equals(step.getTask()) && step.getConfig() != null) {
                    Map<String, Object> config = step.getConfig();
                    Long id = config.get("contractId") instanceof Number ? ((Number) config.get("contractId")).longValue() : null;
                    String name = config.get("contractName") instanceof String ? (String) config.get("contractName") : null;
                    Integer version = config.get("version") instanceof Number ? ((Number) config.get("version")).intValue() : null;
                    if (id != null || (name != null && !name.trim().isEmpty())) {
                        return Optional.of(PipelineDefinition.ContractRef.of(id, id != null ? null : name, version));
                    }
                }
            }
            return Optional.empty();
        }

        /** The parties that review its runs, as the API writes them: internal, customer. */
        public List<String> review() {
            List<String> words = new ArrayList<>();
            if (this.definition != null && this.definition.getSettings() != null) {
                this.definition.getSettings().requiredReviews().forEach(party -> words.add(PipelineDefinition.Review.wordOf(party)));
            }
            return words;
        }
    }

    private static final String COLUMNS = "j.job_id, j.tenant_id, j.job_name, j.job_status, j.job_running_status, t.pipeline_id";
    private static final String FROM = " FROM source_job j LEFT JOIN source_task t ON t.task_detail_id = j.task_detail_id ";

    private final JdbcTemplate sql;
    private final PipelineDefinitionStore definitions;

    public PipelineCatalogue(JdbcTemplate sql, PipelineDefinitionStore definitions) {
        this.sql = sql;
        this.definitions = definitions;
    }

    /** The workspace's pipelines, newest first, after the one {@code afterJobId} names (keyset), at most {@code limit}. */
    public List<Entry> page(long tenantId, Long afterJobId, int limit) {
        List<Entry> entries = new ArrayList<>();
        List<Object[]> rows = this.sql.query("SELECT " + COLUMNS + FROM + "WHERE j.tenant_id = ? AND j.job_status <> 'Delete' AND j.job_id < ? "
            + "ORDER BY j.job_id DESC LIMIT ?", ROW, tenantId, afterJobId == null ? Long.MAX_VALUE : afterJobId, limit);
        // MIG-326: the page's definitions in one query, not one a pipeline.
        List<String> pipelineIds = new ArrayList<>();
        for (Object[] row : rows) {
            if (row[5] != null) {
                pipelineIds.add((String) row[5]);
            }
        }
        Map<String, PipelineDefinitionStore.Stored> latest = this.definitions.latestFor(tenantId, pipelineIds);
        for (Object[] row : rows) {
            PipelineDefinitionStore.Stored stored = row[5] == null ? null : latest.get(((String) row[5]).trim());
            entries.add(this.entry(row, stored == null ? null : stored.definition()));
        }
        return entries;
    }

    /** One pipeline of the workspace by its API id; empty for one that is not a number, not the workspace's, or deleted. */
    public Optional<Entry> find(long tenantId, String pipelineId) {
        long jobId;
        try {
            jobId = Long.parseLong(pipelineId);
        } catch (NumberFormatException notOurs) {
            return Optional.empty();
        }
        return this.sql.query("SELECT " + COLUMNS + FROM + "WHERE j.tenant_id = ? AND j.job_id = ? AND j.job_status <> 'Delete'", ROW,
            tenantId, jobId).stream().findFirst().map(this::entry);
    }

    private static final RowMapper<Object[]> ROW = (rs, n) -> new Object[] {rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
        rs.getString(5), rs.getString(6)};

    private Entry entry(Object[] row) {
        long tenantId = (Long) row[1];
        String pipelineId = (String) row[5];
        PipelineDefinition definition = pipelineId == null ? null
            : this.definitions.latestFor(tenantId, pipelineId).map(PipelineDefinitionStore.Stored::definition).orElse(null);
        return this.entry(row, definition);
    }

    private Entry entry(Object[] row, PipelineDefinition definition) {
        return new Entry((Long) row[0], (Long) row[1], (String) row[2], (String) row[3], (String) row[4], (String) row[5], definition);
    }
}
