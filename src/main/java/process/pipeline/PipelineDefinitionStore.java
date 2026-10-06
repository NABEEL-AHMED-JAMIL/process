package process.pipeline;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * pipeline_definition (V183), through the application's DataSource: under row-level security, so a caller reads and
 * writes its own workspace's definitions only, and a system path (the pre-dispatch phase) reads a run's in the run's
 * workspace or across workspaces as it declares. Rows are never updated: a save is a new version.
 */
@Component
public class PipelineDefinitionStore {

    private static final String COLUMNS = "d.pipeline_definition_id, d.tenant_id, d.pipeline_key, d.version, d.definition::text AS definition, "
        + "d.created_by, d.date_created";

    private final JdbcTemplate jdbc;

    public PipelineDefinitionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One saved version of a pipeline's definition. */
    public static final class Stored {
        public long id;
        public long tenantId;
        public long pipelineKey;
        public int version;
        public String json;
        public Long createdBy;
        public OffsetDateTime dateCreated;

        /** The definition this version holds. It was validated before it was written; a read that fails is a bug. */
        public PipelineDefinition definition() {
            try {
                return DefinitionCodec.fromJson(this.json);
            } catch (DefinitionException ex) {
                throw new IllegalStateException(String.format("Stored pipeline definition %d cannot be read: %s", this.id,
                    ex.getMessage()), ex);
            }
        }
    }

    /**
     * Saves the next version of a pipeline's definition; the workspace is the pipeline's (the trigger's, V102). Two
     * saves at once cannot both take version n: the unique key refuses the second, which the caller reports.
     */
    public Stored save(long pipelineKey, String json, Long createdBy) {
        return this.jdbc.queryForObject("INSERT INTO pipeline_definition AS d (pipeline_key, version, definition, created_by) "
            + "SELECT ?, COALESCE(MAX(version), 0) + 1, ?::json, ? FROM pipeline_definition WHERE pipeline_key = ? "
            + "RETURNING " + COLUMNS, PipelineDefinitionStore::row, pipelineKey, json, createdBy, pipelineKey);
    }

    public Optional<Stored> latest(long pipelineKey) {
        return first(this.jdbc.query("SELECT " + COLUMNS + " FROM pipeline_definition d WHERE d.pipeline_key = ? "
            + "ORDER BY d.version DESC LIMIT 1", PipelineDefinitionStore::row, pipelineKey));
    }

    /**
     * The latest definition of a workspace's pipeline by its public id, when the pipeline is not deleted -- the one a
     * new run follows. When a workspace has two live pipelines with one id, the older pipeline's (as AiStepService
     * reads the first it finds).
     */
    public Optional<Stored> latestFor(long tenantId, String pipelineId) {
        if (pipelineId == null || pipelineId.trim().isEmpty()) {
            return Optional.empty();
        }
        return first(this.jdbc.query("SELECT " + COLUMNS + " FROM pipeline_definition d WHERE d.pipeline_key = ("
            + "SELECT p.pipeline_key FROM pipeline p WHERE p.tenant_id = ? AND p.pipeline_id = ? AND p.status <> 'Delete' "
            + "ORDER BY p.pipeline_key LIMIT 1) ORDER BY d.version DESC LIMIT 1", PipelineDefinitionStore::row, tenantId, pipelineId.trim()));
    }

    /**
     * MIG-326: {@link #latestFor(long, String)} for a page of the workspace's pipelines in one query, by pipeline id (one
     * with no definition is left out): the same row each single read gives -- the lowest-keyed live pipeline of that id,
     * its highest version.
     */
    public Map<String, Stored> latestFor(long tenantId, Collection<String> pipelineIds) {
        Map<String, Stored> found = new HashMap<>();
        List<String> ids = new ArrayList<>();
        for (String id : pipelineIds == null ? Collections.<String>emptyList() : pipelineIds) {
            if (id != null && !id.trim().isEmpty() && !ids.contains(id.trim())) {
                ids.add(id.trim());
            }
        }
        if (ids.isEmpty()) {
            return found;
        }
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(ids);
        this.jdbc.query("SELECT DISTINCT ON (k.pipeline_id) k.pipeline_id AS of_pipeline, " + COLUMNS + " FROM ("
            + "SELECT DISTINCT ON (p.pipeline_id) p.pipeline_id, p.pipeline_key FROM pipeline p WHERE p.tenant_id = ? AND p.pipeline_id IN ("
            + String.join(", ", Collections.nCopies(ids.size(), "?")) + ") AND p.status <> 'Delete' ORDER BY p.pipeline_id, p.pipeline_key) k "
            + "JOIN pipeline_definition d ON d.pipeline_key = k.pipeline_key ORDER BY k.pipeline_id, d.version DESC",
            (ResultSet rs) -> {
                found.put(rs.getString("of_pipeline"), row(rs, 0));
            }, args.toArray());
        return found;
    }

    /**
     * MIG-237: the latest definition of a workspace's pipeline as it stood at {@code asOf} -- the one a run made then
     * followed when nothing pinned it (a run a worker ran). Empty when the pipeline had none yet.
     */
    public Optional<Stored> latestFor(long tenantId, String pipelineId, Timestamp asOf) {
        if (pipelineId == null || pipelineId.trim().isEmpty() || asOf == null) {
            return Optional.empty();
        }
        return first(this.jdbc.query("SELECT " + COLUMNS + " FROM pipeline_definition d WHERE d.pipeline_key = ("
            + "SELECT p.pipeline_key FROM pipeline p WHERE p.tenant_id = ? AND p.pipeline_id = ? AND p.status <> 'Delete' "
            + "ORDER BY p.pipeline_key LIMIT 1) AND d.date_created <= ? ORDER BY d.version DESC LIMIT 1", PipelineDefinitionStore::row,
            tenantId, pipelineId.trim(), asOf));
    }

    public Optional<Stored> byId(long pipelineDefinitionId) {
        return first(this.jdbc.query("SELECT " + COLUMNS + " FROM pipeline_definition d WHERE d.pipeline_definition_id = ?",
            PipelineDefinitionStore::row, pipelineDefinitionId));
    }

    /** Every saved version of a pipeline's definition, newest first. */
    public List<Stored> versions(long pipelineKey) {
        return this.jdbc.query("SELECT " + COLUMNS + " FROM pipeline_definition d WHERE d.pipeline_key = ? ORDER BY d.version DESC",
            PipelineDefinitionStore::row, pipelineKey);
    }

    private static Optional<Stored> first(List<Stored> found) {
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private static Stored row(ResultSet rs, int n) throws SQLException {
        Stored s = new Stored();
        s.id = rs.getLong("pipeline_definition_id");
        s.tenantId = rs.getLong("tenant_id");
        s.pipelineKey = rs.getLong("pipeline_key");
        s.version = rs.getInt("version");
        s.json = rs.getString("definition");
        s.createdBy = (Long) rs.getObject("created_by");
        s.dateCreated = rs.getObject("date_created", OffsetDateTime.class);
        return s;
    }
}
