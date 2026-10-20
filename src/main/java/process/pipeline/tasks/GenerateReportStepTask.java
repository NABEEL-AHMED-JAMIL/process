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
                .description("Placeholders fill from the run, e.g. Weekly summary · {{date}}. Empty: the design's name and the run."))
            .property("formats", JsonSchema.array(JsonSchema.string().enumOf("PDF", "HTML")).maxItems(2).title("Formats")
                .description("PDF and HTML as the design says when empty. The PDF is kept with the run."))
            .property("period", JsonSchema.string().maxLength(60).format("template").title("Period")
                .description("What the report covers, as its {{report.period}}: e.g. {{date}}."))
            .property("brandProfileId", JsonSchema.string().pattern("^[0-9A-HJKMNP-TV-Z]{26}$").title("Brand profile")
                .description("A published brand profile of the workspace; the workspace default when empty."))
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
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> first = inputRows > 0 ? context.firstInputRow() : null;
        if (first != null) {
            values.putAll(first);
        }
        values.putAll(Templates.ofRun(context, (int) Math.min(Integer.MAX_VALUE, inputRows)));
        String usageKey = String.format("pipeline#%d#%s#a%d", context.jobQueueId(), context.stepKey(), context.attempt());
        boolean warnOnly = "CONTINUE_WITH_WARNING".equals(Configs.text(config, "onValidationError", "FAIL_TASK"));

        Path lines = context.scratch().resolve("report-rows.ndjson");
        List<String> columns;
        try (RowSource rows = context.openInput()) {
            columns = new ArrayList<>(rows.columns());
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
            head.put("reason", String.format("Pipeline run #%d, step %s (try %d)", context.jobQueueId(), context.stepKey(), context.attempt()));
            Map<String, Object> pipeline = new LinkedHashMap<>();
            pipeline.put("jobQueueId", context.jobQueueId());
            pipeline.put("jobId", context.jobId());
            pipeline.put("stepKey", context.stepKey());
            pipeline.put("attempt", context.attempt());
            head.put("pipeline", pipeline);
            try (BufferedWriter w = Files.newBufferedWriter(lines, StandardCharsets.UTF_8)) {
                w.write(JSON.writeValueAsString(Map.of("head", head)));
                w.write('\n');
                for (List<Map<String, Object>> batch = rows.next(); batch != null; batch = rows.next()) {
                    for (Map<String, Object> row : batch) {
                        w.write(JSON.writeValueAsString(row));
                        w.write('\n');
                    }
                }
            }
        }

        // The design's fields the rows no longer have: said before the report is made (media's checks then refuse it).
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
                return StepResult.nothing(inputRows);
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
            String fileName = safeName(pdf.path("name").asText("report.pdf"));
            StreamContext.KeptFile kept = context.keepFile(fileName, new ArrayList<>(), inputRows,
                out -> this.studio.file(context.tenantId(), reportId, made, "PDF", out));
            context.recordOutput(RunOutput.file(fileName, "pdf", inputRows, kept.bytes).withSha256(kept.sha256)
                .withPages(pdf.path("pages").asInt(0)));
        }
        JsonNode checks = job.path("checks");
        context.log(String.format("Report %s version %d made from template version %d: %s; checks %d error(s), %d warning(s)%s.",
            job.path("reportLabel").asText(reportId), made, job.path("templateVersion").asInt(), pdf == null ? "no PDF"
                : String.format("PDF %,d page(s)", pdf.path("pages").asInt()), checks.path("errors").asInt(), checks.path("warnings").asInt(),
            checks.path("firstCodes").size() == 0 ? "" : " (" + join(checks.path("firstCodes")) + ")"));
        return StepResult.nothing(inputRows);
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
