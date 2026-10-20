package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.RunOutput;
import process.pipeline.StepReferences;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.backing.ReportStudio;
import process.pipeline.data.RowSource;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * </ul>
 */
@Component
public class GenerateReportStepTask extends RegisteredTask implements StreamingStepTask {

    /** How often the step asks how its job is doing. */
    static final Duration POLL = Duration.ofSeconds(2);
    /** The checks' codes that mean the report itself (its design or the data) is wrong, not that media was busy. */
    static final Set<String> VALIDATION = Set.of("TEMPLATE_INVALID", "DATA_SCHEMA_MISMATCH", "DATA_REQUIRED_MISSING", "BINDING_INVALID",
        "EXPRESSION_INVALID", "DATA_UNREADABLE", "TEMPLATE_VERSION_NOT_PUBLISHED", "TEMPLATE_NOT_FOUND");
    static final Pattern VERSION = Pattern.compile("^(latest|[1-9][0-9]{0,5})$");
    /** The most reports one step makes, one a row. */
    static final int MAX_REPORTS = 200;

    static final TaskSpec SPEC = TaskSpec.builder("generate_report", "Generate report", TaskKind.OUTPUT)
        .description("Makes a Report Studio report from the rows -- with a design made inside this step from its own data, or "
            + "a published template -- and keeps its PDF with the run. The report is a version in Reports, approved and signed "
            + "there like any other.")
        .input(TaskSpec.rows("The rows the report shows."))
        .output(TaskSpec.rows("The same rows, unchanged; the report is in Reports and its PDF is kept with the run."))
        .config(JsonSchema.object()
            .property("design", JsonSchema.string().enumOf("STEP", "TEMPLATE").title("Report design").defaultValue("STEP")
                .description("STEP: the design made inside this step from its data. TEMPLATE: a published template."))
            .required("templateId", JsonSchema.string().pattern("^[0-9A-HJKMNP-TV-Z]{26}$").format(StepReferences.REPORT_STUDIO_TEMPLATE)
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
            .property("maxReports", JsonSchema.integer().minimum(1).maximum(MAX_REPORTS).title("At most reports").defaultValue(20)
                .description("One report per row: an input with more rows fails before any report is made."))
            .property("onValidationError", JsonSchema.string().enumOf("FAIL_TASK", "CONTINUE_WITH_WARNING").title("When the checks refuse it")
                .defaultValue("FAIL_TASK").description("FAIL_TASK: the step fails. CONTINUE_WITH_WARNING: the step completes with no "
                    + "report and a warning on the run.")))
        .backing(TaskSpec.MEDIA)
        .timeoutSeconds(900)
        .aiToolName("generate_report")
        .build();

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
        if (templateId == null) {
            throw new IllegalStateException("The step has no report design: design the report in the step, or pick a template.");
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
        this.make(context, lines, usageKey, warnOnly, inputRows, new HashSet<>());
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
        boolean checked = false;
        int number = 0;
        int made = 0;
        try (RowSource rows = context.openInput()) {
            List<String> columns = new ArrayList<>(rows.columns());
            for (List<Map<String, Object>> batch = rows.next(); batch != null; batch = rows.next()) {
                for (Map<String, Object> row : batch) {
                    number++;
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Generate report was stopped.");
                    }
                    if (!checked) {
                        this.checkDesign(context, templateId, version, columns);
                        checked = true;
                    }
                    Map<String, Object> values = new LinkedHashMap<>(runValues);
                    values.putAll(row);
                    values.put("row", number);
                    Map<String, Object> head = this.head(context, config, templateId, version, values, number);
                    // The report's data is the row itself, one record: the list's first element.
                    head.put("rootPath", "$[0]");
                    Path lines = context.scratch().resolve("report-row-" + number + ".ndjson");
                    try (BufferedWriter w = Files.newBufferedWriter(lines, StandardCharsets.UTF_8)) {
                        w.write(JSON.writeValueAsString(Map.of("head", head)));
                        w.write('\n');
                        w.write(JSON.writeValueAsString(nested(structured(row, json))));
                        w.write('\n');
                    }
                    try {
                        String usageKey = String.format("pipeline#%d#%s#a%d#r%d", context.jobQueueId(), context.stepKey(), context.attempt(), number);
                        if (this.make(context, lines, usageKey, warnOnly, 1, taken)) {
                            made++;
                        }
                    } finally {
                        Files.deleteIfExists(lines);
                    }
                }
            }
        }
        context.log(String.format("%d report(s) made from %d row(s), one a row.", made, number));
        return StepResult.nothing(inputRows);
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
        if (row != null) {
            pipeline.put("row", row);
        }
        head.put("pipeline", pipeline);
        return head;
    }

    /** The design's fields the rows no longer have: said before the report is made (media's checks then refuse it). */
    private void checkDesign(StreamContext context, String templateId, String version, List<String> columns) throws Exception {
        try {
            for (JsonNode t : this.studio.check(context.tenantId(), List.of(templateId), version, columns)) {
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
    }

    /**
     * One report from {@code lines}: queued, followed until done, its PDF kept with the run (a name taken already gets
     * -2, -3). False when the checks refused it and the step only warns; an exception otherwise.
     */
    private boolean make(StreamContext context, Path lines, String usageKey, boolean warnOnly, long rows, Set<String> taken)
        throws Exception {
        Long userId = context.jobOwnerUserId();
        JsonNode job = this.studio.generate(context.tenantId(), userId, pipelineLabel(context), usageKey, lines);
        String jobId = job.path("jobId").asText();
        context.log(String.format("Report queued: job %s for %s.", job.path("label").asText(jobId), job.path("reportLabel").asText("a new report")));
        while (!done(job.path("status").asText())) {
            this.sleeper.sleep(POLL);
            job = this.studio.job(context.tenantId(), jobId);
        }
        String status = job.path("status").asText();
        if (!"SUCCEEDED".equals(status)) {
            String code = job.path("errorCode").asText("");
            String why = String.format("The report was not made (%s%s): %s", status, code.isEmpty() ? "" : ", " + code,
                job.path("error").asText("no reason given"));
            if (warnOnly && VALIDATION.contains(code)) {
                context.warn(why + " The step goes on without a report.");
                return false;
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
        if (pdf != null) {
            String fileName = FileRows.unique(safeName(pdf.path("name").asText("report.pdf")), taken);
            StreamContext.KeptFile kept = context.keepFile(fileName, new ArrayList<>(), rows,
                out -> this.studio.file(context.tenantId(), reportId, made, "PDF", out));
            context.recordOutput(RunOutput.file(fileName, "pdf", rows, kept.bytes).withSha256(kept.sha256)
                .withPages(pdf.path("pages").asInt(0)));
        }
        JsonNode checks = job.path("checks");
        context.log(String.format("Report %s version %d made from template version %d: %s; checks %d error(s), %d warning(s)%s.",
            job.path("reportLabel").asText(reportId), made, job.path("templateVersion").asInt(), pdf == null ? "no PDF"
                : String.format("PDF %,d page(s)", pdf.path("pages").asInt()), checks.path("errors").asInt(), checks.path("warnings").asInt(),
            checks.path("firstCodes").size() == 0 ? "" : " (" + join(checks.path("firstCodes")) + ")"));
        return true;
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
