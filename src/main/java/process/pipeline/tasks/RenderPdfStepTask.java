package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.RunOutput;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.ReportRenderer;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Render PDF (MIG-255): turns the rows the step reads into a PDF report and keeps it with the run as a pdf output, as Save
 * File keeps a csv. media-service renders it (a table under a title and header fields, in the workspace's report template
 * when one is named); Core never draws a page. The title and fields fill from the run ({{run}}, {{date}}, {{rows}}...);
 * columns choose which columns and their order (all, by default). At most maxRows rows (default 1000). The rows pass on
 * unchanged. A retried step bills once: the render's usage key is the run's step and attempt.
 */
@Component
public class RenderPdfStepTask extends RegisteredTask {

    static final int DEFAULT_MAX_ROWS = 1000;
    static final int MAX_ROWS = 5000;

    static final TaskSpec SPEC = TaskSpec.builder("render_pdf", "Render PDF", TaskKind.OUTPUT)
        .description("Renders the rows as a PDF report (media-service) and keeps it with the run as an output file.")
        .input(TaskSpec.rows("The rows to put in the report."))
        .output(TaskSpec.rows("The same rows, unchanged; the PDF is kept with the run."))
        .config(JsonSchema.object()
            .required("fileName", JsonSchema.string().pattern("^[A-Za-z0-9][A-Za-z0-9._-]{0,123}$").title("File name")
                .description("The report's name; .pdf is added when it does not end with it."))
            .property("title", JsonSchema.string().maxLength(200).format("template").title("Title")
                .description("Above the table; fills from the run: Wound assessment, run {{run}}."))
            .property("fields", JsonSchema.map(JsonSchema.string().maxLength(500).format("template")).title("Header fields")
                .description("Label and value pairs above the table: Status = DRAFT for clinician review. A label is plain text."))
            .property("columns", JsonSchema.array(JsonSchema.string().format("column")).maxItems(50).title("Columns")
                .description("Which columns, in order; all of them when empty."))
            .property("orientation", JsonSchema.string().enumOf("auto", "portrait", "landscape").title("Page").defaultValue("auto")
                .description("auto turns the page for a table of more than six columns."))
            .property("watermark", JsonSchema.string().maxLength(40).format("template").title("Watermark")
                .description("Printed across every page, e.g. DRAFT."))
            .property("templateId", JsonSchema.integer().minimum(1).title("Report template")
                .description("A report template of this workspace (Documents > Converter); the default layout when empty."))
            .property("maxRows", JsonSchema.integer().minimum(1).maximum(MAX_ROWS).title("At most rows").defaultValue(DEFAULT_MAX_ROWS)))
        .backing(TaskSpec.MEDIA)
        .timeoutSeconds(600)
        .aiToolName("render_pdf_report")
        .build();

    /** A header label is printed as it is: letters, digits, spaces and . , : ( ) / ' - only. */
    static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 .,:()/'-]{0,59}$");

    /** More columns than this and auto turns the page. */
    static final int PORTRAIT_COLUMNS = 6;

    private final ReportRenderer renderer;

    public RenderPdfStepTask(ReportRenderer renderer) {
        super(SPEC);
        this.renderer = renderer;
    }

    @Override
    public Optional<String> unavailable() {
        return this.renderer.unavailable();
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        for (String label : Configs.textMap(config, "fields").keySet()) {
            if (!LABEL.matcher(label).matches()) {
                problems.add(new DefinitionProblem("fields." + label,
                    "a header label is plain text: letters, digits, spaces and . , : ( ) / ' - (at most 60)"));
            }
        }
        return problems;
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        String fileName = Configs.text(config, "fileName", "report");
        if (!fileName.toLowerCase().endsWith(".pdf")) {
            fileName = fileName + ".pdf";
        }
        Dataset input = context.input();
        int maxRows = Configs.integer(config, "maxRows", DEFAULT_MAX_ROWS);
        if (input.size() > maxRows) {
            throw new IllegalStateException(String.format("The report would have %d rows; this step renders at most %d (maxRows).",
                input.size(), maxRows));
        }
        List<String> columns = Configs.texts(config, "columns");
        if (columns.isEmpty()) {
            columns = new ArrayList<>(input.getColumns());
        }
        for (String column : columns) {
            if (!input.getColumns().contains(column)) {
                throw new IllegalStateException(String.format("The rows have no column '%s'.", column));
            }
        }
        Map<String, Object> runValues = Templates.ofRun(context, input.size());
        String title = Templates.fill(Configs.text(config, "title", fileName.substring(0, fileName.length() - 4)), runValues);
        Map<String, String> fields = new LinkedHashMap<>();
        Configs.textMap(config, "fields").forEach((label, template) -> fields.put(label, Templates.fill(template, runValues)));
        List<List<Object>> rows = new ArrayList<>(input.size());
        for (Map<String, Object> row : input.getRows()) {
            List<Object> values = new ArrayList<>(columns.size());
            for (String column : columns) {
                values.add(row.get(column));
            }
            rows.add(values);
        }
        String usageKey = String.format("pipeline#%d#%s#a%d", context.jobQueueId(), context.stepKey(), context.attempt());
        String page = Configs.text(config, "orientation", "auto");
        String orientation = "auto".equals(page) ? (columns.size() > PORTRAIT_COLUMNS ? "landscape" : "portrait") : page;
        String watermark = Configs.text(config, "watermark", null);
        byte[] pdf = this.renderer.renderPdf(new ReportRenderer.Report(context.tenantId(), usageKey, fileName, title, fields, columns, rows,
            Configs.longValue(config, "templateId"), orientation, watermark == null ? null : Templates.fill(watermark, runValues)));
        context.keepFile(fileName, pdf, input.size(), columns);
        context.recordOutput(RunOutput.file(fileName, "pdf", input.size(), pdf.length));
        context.log(String.format("Rendered %d row(s) as %s (%,d bytes).", input.size(), fileName, pdf.length));
        return StepResult.nothing((long) input.size());
    }
}
