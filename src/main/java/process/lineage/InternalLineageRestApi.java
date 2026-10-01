package process.lineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.tenancy.AcrossTenants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.util.BusinessTime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Finished runs as the Data Catalog's lineage reads them (MIG-287): POST /internal/lineage/runs {afterRunId, limit} --
 * every Completed run after that id, oldest first, in every workspace, with what it read and what it wrote.
 *
 * <ul>
 *   <li>read: the file the run was started for (job_queue.input_bucket/input_key -- an inbox arrival or a form
 *       submission), and each read step's own bucket and key from the definition the run used, when they are literal
 *       (a key with {{placeholders}} is resolved only at run time and not recorded, so it is named but not followed);
 *       a read from a database or an API is named by its task and connection;</li>
 *   <li>wrote: run_output -- a file kept with the run (its run dataset) or an object written to a bucket;</li>
 *   <li>form: the form whose submission started it.</li>
 * </ul>
 * The id is the cursor: Core keeps no other bookmark, and a run that completes after a later one started is seen when
 * its own id comes round, because the reader moves only past runs it was given.
 */
@RestController
@RequestMapping("/internal/lineage")
public class InternalLineageRestApi {

    static final int MAX_RUNS = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Logger logger = LoggerFactory.getLogger(InternalLineageRestApi.class);
    private final JdbcTemplate jdbc;
    private final byte[] token;

    public InternalLineageRestApi(JdbcTemplate jdbc, @Value("${internal.service-token:}") String token) {
        this.jdbc = jdbc;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(value = "/runs", produces = MediaType.APPLICATION_JSON_VALUE)
    @AcrossTenants("the Data Catalog's lineage reads every workspace's finished runs into that workspace's lineage (service token)")
    public ResponseEntity<?> runs(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody(required = false) Map<String, Object> body) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        long after = body != null && body.get("afterRunId") instanceof Number ? ((Number) body.get("afterRunId")).longValue() : 0L;
        int limit = body != null && body.get("limit") instanceof Number ? ((Number) body.get("limit")).intValue() : 200;
        limit = Math.max(1, Math.min(limit, MAX_RUNS));
        return new ResponseEntity<>(this.finishedAfter(after, limit), HttpStatus.OK);
    }

    List<Map<String, Object>> finishedAfter(long after, int limit) {
        List<Map<String, Object>> runs = this.jdbc.query("SELECT q.job_queue_id, q.tenant_id, q.job_id, j.job_name, q.end_time, q.input_bucket, "
            + "q.input_key, t.pipeline_id FROM job_queue q JOIN source_job j ON j.job_id = q.job_id "
            + "LEFT JOIN source_task t ON t.task_detail_id = j.task_detail_id "
            + "WHERE q.job_queue_id > ? AND q.job_status = 'Completed' ORDER BY q.job_queue_id LIMIT ?", (rs, i) -> {
                Map<String, Object> run = new LinkedHashMap<>();
                run.put("runId", rs.getLong("job_queue_id"));
                run.put("tenantId", rs.getLong("tenant_id"));
                run.put("jobId", rs.getLong("job_id"));
                run.put("jobName", rs.getString("job_name"));
                run.put("pipelineId", rs.getString("pipeline_id"));
                Timestamp ended = rs.getTimestamp("end_time");
                run.put("endedAt", ended == null ? null : BusinessTime.wallClockOf(ended).toString());
                List<Map<String, Object>> reads = new ArrayList<>();
                if (rs.getString("input_key") != null) {
                    reads.add(file("input", rs.getString("input_bucket"), rs.getString("input_key")));
                }
                run.put("reads", reads);
                run.put("writes", new ArrayList<Map<String, Object>>());
                return run;
            }, after, limit);
        if (runs.isEmpty()) {
            return runs;
        }
        Map<Long, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> run : runs) {
            byId.put((Long) run.get("runId"), run);
        }
        String ids = byId.keySet().stream().map(String::valueOf).collect(Collectors.joining(","));
        this.readSteps(byId, ids);
        this.jdbc.query("SELECT s.job_queue_id, o.kind, o.name, o.format, o.row_count, o.run_dataset_id, o.bucket_alias, o.object_key "
            + "FROM run_output o JOIN step_execution s ON s.step_execution_id = o.step_execution_id WHERE s.job_queue_id IN (" + ids + ") "
            + "ORDER BY o.run_output_id", rs -> {
                Map<String, Object> wrote = new LinkedHashMap<>();
                wrote.put("kind", rs.getString("kind"));
                wrote.put("name", rs.getString("name"));
                wrote.put("format", rs.getString("format"));
                wrote.put("rows", rs.getObject("row_count"));
                wrote.put("runDatasetId", rs.getObject("run_dataset_id"));
                wrote.put("bucket", rs.getString("bucket_alias"));
                wrote.put("key", rs.getString("object_key"));
                writes(byId.get(rs.getLong("job_queue_id"))).add(wrote);
            });
        this.jdbc.query("SELECT f.job_queue_id, f.form_id, d.name FROM form_submission f LEFT JOIN form_definition d ON d.form_id = f.form_id "
            + "WHERE f.job_queue_id IN (" + ids + ")", rs -> {
                Map<String, Object> run = byId.get(rs.getLong("job_queue_id"));
                Map<String, Object> form = new LinkedHashMap<>();
                form.put("formId", rs.getLong("form_id"));
                form.put("name", rs.getString("name"));
                run.put("form", form);
            });
        return runs;
    }

    /** Each run's read steps, from the definition it ran (the first step execution of each read task names it). */
    private void readSteps(Map<Long, Map<String, Object>> byId, String ids) {
        Map<Long, String> definitions = new LinkedHashMap<>();
        this.jdbc.query("SELECT DISTINCT s.job_queue_id, d.definition::text AS definition FROM step_execution s "
            + "JOIN pipeline_definition d ON d.pipeline_definition_id = s.pipeline_definition_id WHERE s.job_queue_id IN (" + ids + ")",
            rs -> {
                definitions.putIfAbsent(rs.getLong("job_queue_id"), rs.getString("definition"));
            });
        for (Map.Entry<Long, String> d : definitions.entrySet()) {
            JsonNode steps;
            try {
                steps = JSON.readTree(d.getValue()).path("steps");
            } catch (IOException unreadable) {
                continue;
            }
            List<Map<String, Object>> reads = reads(byId.get(d.getKey()));
            for (JsonNode step : steps) {
                String task = step.path("task").asText("");
                if (!task.startsWith("read_")) {
                    continue;
                }
                JsonNode config = step.path("config");
                String bucket = config.path("bucket").asText(null);
                String key = config.path("key").asText(null);
                if (bucket != null && key != null) {
                    Map<String, Object> read = file(task, bucket, key);
                    if (key.contains("{{")) {
                        read.put("templated", true);
                    }
                    if (reads.stream().noneMatch(r -> bucket.equals(r.get("bucket")) && key.equals(r.get("key")))) {
                        reads.add(read);
                    }
                } else if (bucket == null && key == null && "read_file".equals(task)) {
                    continue;
                } else {
                    Map<String, Object> read = new LinkedHashMap<>();
                    read.put("task", task);
                    read.put("kind", "source");
                    String connection = config.path("connectionId").asText(config.path("sourceId").asText(config.path("url").asText(null)));
                    read.put("connection", connection);
                    read.put("name", step.path("name").asText(step.path("key").asText(task)));
                    reads.add(read);
                }
            }
        }
    }

    private static Map<String, Object> file(String task, String bucket, String key) {
        Map<String, Object> read = new LinkedHashMap<>();
        read.put("task", task);
        read.put("kind", "file");
        read.put("bucket", bucket);
        read.put("key", key);
        return read;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> reads(Map<String, Object> run) {
        return run == null ? Collections.emptyList() : (List<Map<String, Object>>) run.get("reads");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> writes(Map<String, Object> run) {
        return run == null ? new ArrayList<>() : (List<Map<String, Object>>) run.get("writes");
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused a lineage read without the internal token.");
        }
        return ok;
    }
}
