package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.RunOutput;
import process.pipeline.StepReferences;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.backing.ReportStudio;
import process.pipeline.data.RowSource;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.HashSet;
import process.pipeline.data.Values;

/**
 * Generate report (Report Studio phase 5, plan 1.11 and 1.11.1): a Report Studio report from the rows the step reads, made
 * from the step's own design (designed inside the step, from its data) or from a published template of the workspace. The
 * report is a version in the library like any other -- frozen data, brand, checks, approval and signing -- and its PDF is
 * kept with the run as an output; the rows pass on unchanged.
 *
 * <ul>
 *   <li>The rows go to media-service as NDJSON from the step's scratch file (a head line naming the design and the run,
 *       then one JSON object a row): no JSON body of every row in memory, ids only in the run's metadata.</li>
 *   <li>One job a step attempt: the idempotency key is the run's step and attempt (pipeline#run#step#a<n>), so a try that
 *       is asked again answers the same job, and the report is billed once.</li>
 *   <li>The step follows the job until it is done (bounded by the step's time limit). A report the checks refuse fails the
 *       step -- or, with onValidationError CONTINUE_WITH_WARNING, the step completes with no report and a warning.</li>
 *   <li>Before it is made, the step names the design's fields its rows do not have (media's check), so a changed upstream
 *       step says what it broke.</li>
 *   <li>jsonColumns: columns holding JSON text (an AI answer's lists) reach the report as the lists and objects they hold.</li>
 *   <li>One report per row (perRow, opt-in): each row is its own report -- its own job, idempotency key
 *       (pipeline#run#step#a<n>#r<row>), name from the row's columns and lineage (pipeline.row) -- with the row as the
 *       design's single record (rootPath $[0]; a column a.b as a: {b}); at most maxReports rows. The default stays one
 *       report of all the rows (a design's repeat-for-each section makes a digest of them).</li>
 *   <li>MIG-421 (one report per row, in an item run): each report is kept for its item as soon as it is made (run_item_report,
 *       by the item and a hash of its data, the design, its published version and the step's settings), even when a later
 *       row fails. A retry that reaches the step reuses it -- the PDF is read again from the report's own version, Report
 *       Studio is not asked to make one, nothing is billed, the report's version and signature are untouched -- and makes
 *       only the reports whose item has none, or whose data or design changed.</li>
 * </ul>
 */
@Component
public class GenerateReportStepTask extends RegisteredTask implements StreamingStepTask {

    /** How often the step asks how its job is doing. */
    static final Duration POLL = Duration.ofSeconds(2);
    /**
     * The refusals that are "the checks refuse it" (onValidationError), in media's own codes (Report Studio's ErrorCode): the
     * design, the data against it, the page limit and its pictures. ZT-S1: this named DATA_REQUIRED_MISSING and BINDING_INVALID,
     * which media never sends, so a required field missing or a binding the data does not have failed the step even with
     * CONTINUE_WITH_WARNING. A busy or unreachable service is not a check: the step's tries decide.
     */
    static final Set<String> VALIDATION = Set.of("TEMPLATE_INVALID", "DATA_SCHEMA_MISMATCH", "REQUIRED_FIELD_MISSING", "BINDING_NOT_RESOLVED",
        "EXPRESSION_INVALID", "DATA_UNREADABLE", "TEMPLATE_VERSION_NOT_PUBLISHED", "TEMPLATE_NOT_FOUND", "LIMIT_EXCEEDED", "ASSET_NOT_FOUND",
        "IMAGE_UNREADABLE", "CHART_FIELD_TYPE_INVALID", "UNSUPPORTED_COMPONENT");
    static final Pattern VERSION = Pattern.compile("^(latest|[1-9][0-9]{0,5})$");
    /** The most reports one step makes, one a row. */
    static final int MAX_REPORTS = 200;
    /** The longest pipeline name the head carries (media keeps it in the report version's lineage). */
    static final int MAX_PIPELINE_NAME = 160;
    /**
     * ZT-R40 (AI designer retest, 2026-10-26): a step with neither its own design nor a template -- said at save (the save is
     * refused, 422) and, for a version stored before, by the step itself when its run starts, never left at Start.
     */
    public static final String NO_DESIGN = "This report step has no design yet: open the step and design it or pick a saved template.";

    static final TaskSpec SPEC = TaskSpec.builder("generate_report", "Generate report", TaskKind.OUTPUT)
        .repeatSafe(true)  // a repeat writes the same thing again (owner 2026-10-21: passing errors are tried again)
        .description("Makes a Report Studio report from the rows -- with a design made inside this step from its own data, or "
            + "a published template -- and keeps its PDF with the run. The report is a version in Reports, approved and signed "
            + "there like any other.")
        .input(TaskSpec.rows("The rows the report shows."))
        .output(TaskSpec.rows("The same rows, unchanged; the report is in Reports and its PDF is kept with the run."))
        .config(JsonSchema.object()
            .property("design", JsonSchema.string().enumOf("STEP", "TEMPLATE").title("Report design").defaultValue("STEP")
                .description("STEP: the design made inside this step from its data. TEMPLATE: a published template."))
            // ZT-R40: not "required" in the schema -- a step without a design is check()'s, in words a person can act on.
            .property("templateId", JsonSchema.string().pattern("^[0-9A-HJKMNP-TV-Z]{26}$").format(StepReferences.REPORT_STUDIO_TEMPLATE)
                .title("Design or template").description("The step's own design, or a template of this workspace."))
            .property("templateVersion", JsonSchema.string().pattern(VERSION.pattern()).title("Version").defaultValue("latest")
                .description("latest: its newest published version; or a version number, pinned."))
            .property("reportName", JsonSchema.string().maxLength(200).format("template").title("Report name")
                .description("Placeholders fill from the run, e.g. Weekly summary · {{date}}, and one report per row from the row's "
                    + "columns. Empty: the design's name and the run."))
            .property("formats", JsonSchema.array(JsonSchema.string().enumOf("PDF", "HTML")).maxItems(2).title("Formats")
                .description("PDF and HTML as the design says when empty. The PDF is kept with the run."))
            .property("period", JsonSchema.string().maxLength(60).format("template").title("Period")
                .description("What the report covers, as its {{report.period}}: e.g. {{date}}."))
            .property("brandProfileId", JsonSchema.string().pattern("^[0-9A-HJKMNP-TV-Z]{26}$").title("Brand profile")
                .description("A published brand profile of the workspace; the workspace default when empty."))
            .property("jsonColumns", JsonSchema.array(JsonSchema.string().minLength(1).maxLength(255).format("column"))
                .maxItems(200).title("JSON columns")
                .description("Columns holding JSON text (an AI answer's lists and objects): the report reads them as the lists and "
                    + "objects they hold, so a table or a list in the design can show them."))
            .property("perRow", JsonSchema.bool().title("One report per row").defaultValue(false)
                .description("Each row is its own report: its own version in Reports, named from its columns (Call · {{file_name}}), "
                    + "the row as the design's data (a column a.b as a: {b}). Off: one report of all the rows."))
            .property(AtOnce.SETTING, AtOnce.setting("One report per row: reports made"))
            .property("maxReports", JsonSchema.integer().minimum(1).maximum(MAX_REPORTS).title("At most reports").defaultValue(20)
                .description("One report per row: an input with more rows fails before any report is made."))
            .property("onValidationError", JsonSchema.string().enumOf("FAIL_TASK", "CONTINUE_WITH_WARNING").title("When the checks refuse it")
                .defaultValue("FAIL_TASK").description("FAIL_TASK: the step fails. CONTINUE_WITH_WARNING: the step completes with no "
                    + "report and a warning on the run.")))
        .backing(TaskSpec.MEDIA)
        .timeoutSeconds(900)
        .aiToolName("generate_report")
        .items(ItemRole.EACH).itemsWhen("perRow", true).build();

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReportStudio studio;
    private final Sleeper sleeper;

    /** How the step waits between asks (a test's is instant). */
    interface Sleeper {
        void sleep(Duration d) throws InterruptedException;
    }

    @Autowired
    public GenerateReportStepTask(ReportStudio studio) {
        this(studio, d -> Thread.sleep(d.toMillis()));
    }

    GenerateReportStepTask(ReportStudio studio, Sleeper sleeper) {
        super(SPEC);
        this.studio = studio;
        this.sleeper = sleeper;
    }

    @Override
    public Optional<String> unavailable() {
        return this.studio.unavailable();
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        String templateId = Configs.text(config, "templateId", null);
        if (templateId == null || templateId.trim().isEmpty()) {
            problems.add(new DefinitionProblem("templateId", NO_DESIGN));
        }
        String version = Configs.text(config, "templateVersion", "latest");
        if (!VERSION.matcher(version).matches()) {
            problems.add(new DefinitionProblem("templateVersion", "latest, or a published version's number"));
        }
        return problems;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        String templateId = Configs.text(config, "templateId", null);
        if (templateId == null || templateId.trim().isEmpty()) {
            throw new IllegalStateException(NO_DESIGN);
        }
        String version = Configs.text(config, "templateVersion", "latest");
        long inputRows = context.inputSize();
        boolean warnOnly = "CONTINUE_WITH_WARNING".equals(Configs.text(config, "onValidationError", "FAIL_TASK"));
        List<String> json = Configs.texts(config, "jsonColumns");
        if (Configs.bool(config, "perRow", false)) {
            return this.perRow(context, config, templateId, version, inputRows, warnOnly, json);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> first = inputRows > 0 ? context.firstInputRow() : null;
        if (first != null) {
            values.putAll(first);
        }
        values.putAll(Templates.ofRun(context, (int) Math.min(Integer.MAX_VALUE, inputRows)));
        String usageKey = String.format("pipeline#%d#%s#a%d", context.jobQueueId(), context.stepKey(), context.attempt());

        Path lines = context.scratch().resolve("report-rows.ndjson");
        List<String> columns;
        try (RowSource rows = context.openInput()) {
            columns = new ArrayList<>(rows.columns());
            Map<String, Object> head = this.head(context, config, templateId, version, values, null);
            try (BufferedWriter w = Files.newBufferedWriter(lines, StandardCharsets.UTF_8)) {
                w.write(JSON.writeValueAsString(Map.of("head", head)));
                w.write('\n');
                for (List<Map<String, Object>> batch = rows.next(); batch != null; batch = rows.next()) {
                    for (Map<String, Object> row : batch) {
                        w.write(JSON.writeValueAsString(structured(row, json)));
                        w.write('\n');
                    }
                }
            }
        }
        this.checkDesign(context, templateId, version, columns);
        this.make(context, lines, usageKey, warnOnly, inputRows, new HashSet<>(), null);
        return StepResult.nothing(inputRows);
    }

    /**
     * One report per row: each row is its own report -- its own version in the library, named from the row's columns
     * ({{call.file}}), its data the row itself as one record (a column named a.b as a: {b}; the JSON columns as lists),
     * its lineage the run, the step and the row (pipeline.row), billed once a row per attempt. At most maxReports rows; a
     * row the checks refuse fails the step, or with CONTINUE_WITH_WARNING is warned of while the others are made.
     */
    private StepResult perRow(StreamContext context, Map<String, Object> config, String templateId, String version, long inputRows,
        boolean warnOnly, List<String> json) throws Exception {
        int maxReports = Configs.integer(config, "maxReports", 20);
        if (inputRows > maxReports) {
            throw new IllegalStateException(String.format("The input has %,d rows; this step makes at most %d reports (maxReports).", inputRows,
                maxReports));
        }
        Map<String, Object> runValues = Templates.ofRun(context, (int) Math.min(Integer.MAX_VALUE, inputRows));
        Set<String> taken = new HashSet<>();
        int number = 0;
        int made = 0;
        int reused = 0;
        int atOnce = AtOnce.of(config);
        if (atOnce > 1) {
            // MIG-418: the rows are at most maxReports, so they are held to be sent a few at a time.
            List<Map<String, Object>> all = new ArrayList<>();
            List<String> columns;
            try (RowSource rows = context.openInput()) {
                columns = new ArrayList<>(rows.columns());
                for (List<Map<String, Object>> batch = rows.next(); batch != null; batch = rows.next()) {
                    all.addAll(batch);
                }
            }
            String resolved = all.isEmpty() ? version : this.checkDesign(context, templateId, version, columns);
            context.log(String.format("Calls at once: %d (the workspace's calls together at most %d).", atOnce, AtOnce.workspaceCap()));
            // MIG-420: each row's item key, on the step's thread, and the time each report took.
            String[] tags = new String[all.size()];
            String[] hashes = new String[all.size()];
            // MIG-421: the rows whose item has its report already (same data and design) are not sent: reused in row order below.
            boolean[] keptBefore = new boolean[all.size()];
            List<Integer> toMake = new ArrayList<>();
            for (int i = 0; i < tags.length; i++) {
                tags[i] = context.rowTag(all.get(i), i);
                hashes[i] = dataHash(templateId, resolved, config, nested(structured(all.get(i), json)));
                keptBefore[i] = context.keptReport(all.get(i), i, hashes[i]).isPresent();
                if (!keptBefore[i]) {
                    toMake.add(i);
                }
            }
            long[] took = new long[all.size()];
            // Each report queued and followed at once with others; kept with the run afterwards, in row order.
            List<AtOnce.Outcome<JsonNode>> jobs = AtOnce.run(context.tenantId(), toMake.size(), atOnce, k -> {
                int i = toMake.get(k);
                long began = System.nanoTime();
                try {
                    return this.queued(context, config, templateId, version, runValues, all.get(i), i + 1, tags[i], json);
                } catch (ReportStudio.Busy busy) {
                    throw new AtOnce.Busy(busy.getMessage(), busy.retryAfterSeconds);
                } finally {
                    took[i] = (System.nanoTime() - began) / 1_000_000L;
                }
            });
            Map<Integer, AtOnce.Outcome<JsonNode>> byRow = new LinkedHashMap<>();
            for (int k = 0; k < toMake.size(); k++) {
                byRow.put(toMake.get(k), jobs.get(k));
            }
            for (int i = 0; i < all.size(); i++) {
                if (keptBefore[i]) {
                    long began = System.nanoTime();
                    if (this.reuse(context, all.get(i), i, hashes[i], taken) != null) {
                        number++;
                        reused++;
                        context.itemTook(all.get(i), (System.nanoTime() - began) / 1_000_000L);
                        continue;
                    }
                    // Its PDF could not be read again: made now, on the step's thread.
                    number++;
                    Made now = this.makeRow(context, config, templateId, version, runValues, all.get(i), i + 1, tags[i], json, warnOnly, taken);
                    kept(context, all.get(i), i, hashes[i], now);
                    made += now == null ? 0 : 1;
                    continue;
                }
                AtOnce.Outcome<JsonNode> job = byRow.get(i);
                if (job == null || !job.made) {
                    break;
                }
                number++;
                // MIG-420: the row's item, on the step's thread, in row order.
                context.itemTook(all.get(i), took[i]);
                if (job.error != null) {
                    context.itemFailed(all.get(i), job.error.getMessage());
                    throw job.error;
                }
                try {
                    Made one = this.keep(context, job.value, warnOnly, 1, taken, all.get(i));
                    if (one != null) {
                        made++;
                        kept(context, all.get(i), i, hashes[i], one);
                    }
                } catch (InterruptedException stopped) {
                    throw stopped;
                } catch (Exception notKept) {
                    context.itemFailed(all.get(i), notKept.getMessage());
                    throw notKept;
                }
            }
            context.log(madeLine(made, reused, number));
            return StepResult.nothing(inputRows);
        }
        String resolved = null;
        try (RowSource rows = context.openInput()) {
            List<String> columns = new ArrayList<>(rows.columns());
            for (List<Map<String, Object>> batch = rows.next(); batch != null; batch = rows.next()) {
                for (Map<String, Object> row : batch) {
                    number++;
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Generate report was stopped.");
                    }
                    if (resolved == null) {
                        resolved = this.checkDesign(context, templateId, version, columns);
                    }
                    long began = System.nanoTime();
                    // MIG-421: the row's item has its report already (same data and design): reused, not made or billed again.
                    String hash = dataHash(templateId, resolved, config, nested(structured(row, json)));
                    if (this.reuse(context, row, number - 1, hash, taken) != null) {
                        reused++;
                        context.itemTook(row, (System.nanoTime() - began) / 1_000_000L);
                        continue;
                    }
                    try {
                        Made one = this.makeRow(context, config, templateId, version, runValues, row, number, context.rowTag(row, number - 1), json,
                            warnOnly, taken);
                        if (one != null) {
                            made++;
                            // Kept for its item as soon as it is made: a later row's failure does not throw it away.
                            kept(context, row, number - 1, hash, one);
                        }
                        context.itemTook(row, (System.nanoTime() - began) / 1_000_000L);
                    } catch (InterruptedException stopped) {
                        throw stopped;
                    } catch (Exception reportFailed) {
                        context.itemFailed(row, reportFailed.getMessage());
                        context.itemTook(row, (System.nanoTime() - began) / 1_000_000L);
                        throw reportFailed;
                    }
                }
            }
        }
        context.log(madeLine(made, reused, number));
        return StepResult.nothing(inputRows);
    }

    /** The step's line: reports made and reused, one a row. */
    static String madeLine(int made, int reused, int rows) {
        return reused == 0 ? String.format("%d report(s) made from %d row(s), one a row.", made, rows)
            : String.format("%d report(s) made and %d reused (same data and design, not made or billed again) from %d row(s), one a row.",
                made, reused, rows);
    }

    /** One row's report, made on the step's thread: its lines written, queued under the row's key, followed and kept. */
    private Made makeRow(StreamContext context, Map<String, Object> config, String templateId, String version, Map<String, Object> runValues,
        Map<String, Object> row, int number, String tag, List<String> json, boolean warnOnly, Set<String> taken) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>(runValues);
        values.putAll(row);
        values.put("row", number);
        Map<String, Object> head = this.head(context, config, templateId, version, values, number);
        // The report's data is the row itself, one record: the list's first element.
        head.put("rootPath", "$[0]");
        Path lines = context.scratch().resolve("report-row-" + number + ".ndjson");
        try {
            try (BufferedWriter w = Files.newBufferedWriter(lines, StandardCharsets.UTF_8)) {
                w.write(JSON.writeValueAsString(Map.of("head", head)));
                w.write('\n');
                w.write(JSON.writeValueAsString(nested(structured(row, json))));
                w.write('\n');
            }
            // MIG-420: in an item run the report is keyed by the row's item (rowTag); the attempt keeps a retry's own job.
            String usageKey = String.format("pipeline#%d#%s#a%d#r%s", context.jobQueueId(), context.stepKey(), context.attempt(), tag);
            return this.make(context, lines, usageKey, warnOnly, 1, taken, row);
        } finally {
            Files.deleteIfExists(lines);
        }
    }

    /** MIG-418: one row's report written to its own lines file, queued and followed until done (on a worker thread). */
    private JsonNode queued(StreamContext context, Map<String, Object> config, String templateId, String version,
        Map<String, Object> runValues, Map<String, Object> row, int number, String tag, List<String> json) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>(runValues);
        values.putAll(row);
        values.put("row", number);
        Map<String, Object> head = this.head(context, config, templateId, version, values, number);
        head.put("rootPath", "$[0]");
        Path lines = context.scratch().resolve("report-row-" + number + ".ndjson");
        try {
            try (BufferedWriter w = Files.newBufferedWriter(lines, StandardCharsets.UTF_8)) {
                w.write(JSON.writeValueAsString(Map.of("head", head)));
                w.write('\n');
                w.write(JSON.writeValueAsString(nested(structured(row, json))));
                w.write('\n');
            }
            String usageKey = String.format("pipeline#%d#%s#a%d#r%s", context.jobQueueId(), context.stepKey(), context.attempt(), tag);
            return this.follow(context, lines, usageKey);
        } finally {
            Files.deleteIfExists(lines);
        }
    }

    /** The head line: the design, its version, the name and period filled from {@code values}, and the run (and row). */
    private Map<String, Object> head(StreamContext context, Map<String, Object> config, String templateId, String version,
        Map<String, Object> values, Integer row) {
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("templateId", templateId);
        head.put("version", version);
        String name = Configs.text(config, "reportName", null);
        if (name != null) {
            head.put("name", Templates.fill(name, values));
        }
        String period = Configs.text(config, "period", null);
        if (period != null) {
            head.put("period", Templates.fill(period, values));
        }
        List<String> formats = Configs.texts(config, "formats");
        if (!formats.isEmpty()) {
            head.put("formats", formats);
        }
        String brand = Configs.text(config, "brandProfileId", null);
        if (brand != null) {
            head.put("brandProfileId", brand);
        }
        head.put("reason", String.format("Pipeline run #%d, step %s (try %d)%s", context.jobQueueId(), context.stepKey(), context.attempt(),
            row == null ? "" : ", row " + row));
        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put("jobQueueId", context.jobQueueId());
        pipeline.put("jobId", context.jobId());
        pipeline.put("stepKey", context.stepKey());
        pipeline.put("attempt", context.attempt());
        String pipelineName = pipelineName(context);
        if (pipelineName != null) {
            pipeline.put("pipelineName", pipelineName); // the report version's lineage names the pipeline
        }
        if (row != null) {
            pipeline.put("row", row);
        }
        // ZT-M21: a sensitive pipeline's report is a sensitive file -- media's library asks Download sensitive files for it.
        if (context.sensitive()) {
            pipeline.put("sensitive", true);
        }
        head.put("pipeline", pipeline);
        return head;
    }

    /**
     * The design's fields the rows no longer have: said before the report is made (media's checks then refuse it). Returns the
     * version the reports are made from -- the pinned one, or "latest" resolved to its published number (MIG-421: part of
     * what a kept report was made from); {@code version} itself when media could not say.
     */
    private String checkDesign(StreamContext context, String templateId, String version, List<String> columns) throws Exception {
        String resolved = version;
        try {
            for (JsonNode t : this.studio.check(context.tenantId(), List.of(templateId), version, columns)) {
                if ("latest".equals(version) && t.path("currentPublishedVersion").canConvertToInt()
                    && t.path("currentPublishedVersion").asInt() > 0) {
                    resolved = String.valueOf(t.path("currentPublishedVersion").asInt());
                }
                if (!t.path("found").asBoolean(false)) {
                    throw new IllegalStateException("The step's report design is not there any more (" + templateId + "): design it again, "
                        + "or pick a template.");
                }
                List<String> missing = new ArrayList<>();
                t.path("missingFields").forEach(f -> missing.add(f.asText()));
                if (!missing.isEmpty()) {
                    context.warn(String.format("The design '%s' reads %s, which this step's rows do not have.", t.path("name").asText(),
                        String.join(", ", missing)));
                }
            }
        } catch (IOException unanswered) {
            context.log("The design's fields were not checked first: " + unanswered.getMessage());
        }
        return resolved;
    }

    /**
     * One report from {@code lines}: queued, followed until done, its PDF kept with the run (a name taken already gets
     * -2, -3). False when the checks refused it and the step only warns; an exception otherwise.
     */
    private Made make(StreamContext context, Path lines, String usageKey, boolean warnOnly, long rows, Set<String> taken,
        Map<String, Object> forRow) throws Exception {
        return this.keep(context, this.follow(context, lines, usageKey), warnOnly, rows, taken, forRow);
    }

    /** MIG-421: a report made: its id and version, label, its PDF's name (as kept with the run) and pages. */
    static final class Made {
        final String reportId;
        final int version;
        final String label;
        final String fileName;
        final Integer pages;

        Made(String reportId, int version, String label, String fileName, Integer pages) {
            this.reportId = reportId;
            this.version = version;
            this.label = label;
            this.fileName = fileName;
            this.pages = pages;
        }
    }

    /** A report queued from {@code lines} and followed until its job is done: the finished job. */
    private JsonNode follow(StreamContext context, Path lines, String usageKey) throws Exception {
        Long userId = context.jobOwnerUserId();
        JsonNode job = this.studio.generate(context.tenantId(), userId, pipelineLabel(context), usageKey, lines);
        String jobId = job.path("jobId").asText();
        context.log(String.format("Report queued: job %s for %s.", job.path("label").asText(jobId), job.path("reportLabel").asText("a new report")));
        while (!done(job.path("status").asText())) {
            this.sleeper.sleep(POLL);
            job = this.studio.job(context.tenantId(), jobId);
        }
        return job;
    }

    /** A finished job's report: its PDF kept with the run (on the step's own thread, in row order); null when warned past. */
    private Made keep(StreamContext context, JsonNode job, boolean warnOnly, long rows, Set<String> taken, Map<String, Object> forRow)
        throws Exception {
        String status = job.path("status").asText();
        if (!"SUCCEEDED".equals(status)) {
            String code = job.path("errorCode").asText("");
            String why = String.format("The report was not made (%s%s): %s", status, code.isEmpty() ? "" : ", " + code,
                job.path("error").asText("no reason given"));
            if (warnOnly && VALIDATION.contains(code)) {
                context.warn(why + " The step goes on without a report.");
                if (forRow != null) {
                    context.itemFailed(forRow, why);
                }
                return null;
            }
            throw new IllegalStateException(why);
        }
        String reportId = job.path("reportId").asText();
        int made = job.path("resultVersion").asInt();
        JsonNode pdf = null;
        for (JsonNode a : job.path("artifacts")) {
            if ("PDF".equals(a.path("format").asText())) {
                pdf = a;
            }
        }
        String fileName = null;
        if (pdf != null) {
            fileName = FileRows.unique(safeName(pdf.path("name").asText("report.pdf")), taken);
            StreamContext.KeptFile kept = context.keepFile(fileName, new ArrayList<>(), rows,
                out -> this.studio.file(context.tenantId(), reportId, made, "PDF", out));
            RunOutput output = RunOutput.file(fileName, "pdf", rows, kept.bytes).withSha256(kept.sha256).withPages(pdf.path("pages").asInt(0));
            if (forRow == null) {
                context.recordOutput(output);
            } else {
                // MIG-420: the row's report is its item's.
                context.recordOutput(output, forRow);
            }
        }
        JsonNode checks = job.path("checks");
        context.log(String.format("Report %s version %d made from template version %d: %s; checks %d error(s), %d warning(s)%s.",
            job.path("reportLabel").asText(reportId), made, job.path("templateVersion").asInt(), pdf == null ? "no PDF"
                : String.format("PDF %,d page(s)", pdf.path("pages").asInt()), checks.path("errors").asInt(), checks.path("warnings").asInt(),
            checks.path("firstCodes").size() == 0 ? "" : " (" + join(checks.path("firstCodes")) + ")"));
        return new Made(reportId, made, job.path("reportLabel").asText(reportId), fileName, pdf == null ? null : pdf.path("pages").asInt(0));
    }

    /**
     * MIG-421: the report an earlier attempt (or this one) made for this row's item from the same data and design, kept with
     * the run again -- its PDF read from its own version, nothing made, nothing billed; null when there is none, or its PDF
     * cannot be read any more (the report is then made).
     */
    private Made reuse(StreamContext context, Map<String, Object> row, int index, String hash, Set<String> taken) throws InterruptedException {
        Optional<StepContext.KeptReport> found = context.keptReport(row, index, hash);
        if (!found.isPresent()) {
            return null;
        }
        StepContext.KeptReport kept = found.get();
        String fileName = FileRows.unique(safeName(kept.fileName == null ? "report.pdf" : kept.fileName), taken);
        try {
            StreamContext.KeptFile file = context.keepFile(fileName, new ArrayList<>(), 1,
                out -> this.studio.file(context.tenantId(), kept.reportId, kept.version, "PDF", out));
            RunOutput output = RunOutput.file(fileName, "pdf", 1, file.bytes).withSha256(file.sha256)
                .withPages(kept.pages == null ? 0 : kept.pages);
            context.recordOutput(output, row);
        } catch (InterruptedException stopped) {
            throw stopped;
        } catch (Exception unread) {
            taken.remove(fileName);
            context.log(String.format("Report %s version %d could not be read again (%s); it is made again.", kept.label, kept.version,
                unread.getMessage()));
            return null;
        }
        context.itemReused(row, String.format("Report %s v%d reused: same data and design (made in attempt %d), not made or billed again.",
            kept.label, kept.version, kept.madeAttempt), kept.madeAttempt);
        context.log(String.format("Report %s version %d reused for row %d: its data and design are unchanged.", kept.label, kept.version, index + 1));
        return new Made(kept.reportId, kept.version, kept.label, fileName, kept.pages);
    }

    /**
     * MIG-421: what one row's report is made from, as a hash: the design and the version it is made from, the step's settings
     * that shape it (not its name's run values: a retry's date is not a change), and the row as the design reads it.
     */
    static String dataHash(String templateId, String resolvedVersion, Map<String, Object> config, Map<String, Object> record) {
        Map<String, Object> what = new TreeMap<>();
        what.put("templateId", templateId);
        what.put("version", resolvedVersion);
        for (String setting : new String[] {"design", "reportName", "formats", "period", "brandProfileId", "jsonColumns"}) {
            what.put(setting, config == null ? null : config.get(setting));
        }
        what.put("record", record);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(what));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** MIG-421: the report just made, kept for its row's item. */
    private static void kept(StreamContext context, Map<String, Object> row, int index, String hash, Made made) {
        if (made != null && made.reportId != null && !made.reportId.isEmpty() && made.version > 0) {
            context.reportKept(row, index, hash, new StepContext.KeptReport(made.reportId, made.version, made.label, made.fileName, made.pages,
                context.attempt()));
        }
    }

    /** The row with its JSON columns' text as the lists and objects it holds. */
    static Map<String, Object> structured(Map<String, Object> row, List<String> json) {
        if (json.isEmpty()) {
            return row;
        }
        Map<String, Object> out = new LinkedHashMap<>(row);
        for (String column : json) {
            if (out.containsKey(column)) {
                out.put(column, Values.structured(out.get(column)));
            }
        }
        return out;
    }

    /** A record as a design reads one: a column named a.b becomes a: {b: ...}, unless a is a column of its own. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> nested(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> cell : row.entrySet()) {
            String name = cell.getKey();
            int dot = name.indexOf('.');
            if (dot <= 0 || dot == name.length() - 1 || (row.containsKey(name.substring(0, dot)) && !(row.get(name.substring(0, dot)) instanceof Map))) {
                out.putIfAbsent(name, cell.getValue());
                continue;
            }
            Map<String, Object> at = out;
            String[] parts = name.split("\\.");
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = at.get(parts[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    at.put(parts[i], next);
                }
                at = (Map<String, Object>) next;
            }
            at.put(parts[parts.length - 1], cell.getValue());
        }
        return out;
    }

    static boolean done(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    /** The pipeline's name for the head: trimmed, at most {@value #MAX_PIPELINE_NAME} characters; null when it has none. */
    static String pipelineName(StreamContext context) {
        String name = context.pipelineName();
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        name = name.trim();
        return name.length() > MAX_PIPELINE_NAME ? name.substring(0, MAX_PIPELINE_NAME) : name;
    }

    /** Who the report says made it: the pipeline's run. */
    static String pipelineLabel(StreamContext context) {
        return "Pipeline run #" + context.jobQueueId();
    }

    /** The run output's name: the report's own file name, made safe for the run's file area. */
    static String safeName(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^[._-]+", "");
        if (clean.isEmpty()) {
            clean = "report";
        }
        if (clean.length() > 120) {
            clean = clean.substring(0, 120);
        }
        return clean.toLowerCase().endsWith(".pdf") ? clean : clean + ".pdf";
    }

    private static String join(JsonNode codes) {
        List<String> out = new ArrayList<>();
        codes.forEach(c -> out.add(c.asText()));
        return String.join(", ", out);
    }
}
