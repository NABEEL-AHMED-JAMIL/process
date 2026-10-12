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
 *
 * MIG-346: {@code sections} draws several tables in one report, each under its own title (and an optional note): the rows
 * of an earlier step ({@code input}) or of this step's input, its own columns and maxRows. Without sections the report is
 * the one table it always was. The title, header fields, notes and watermark fill from the run and from the first row of
 * the step's input ({{column}}): "Patients: {{patient_count}}" reads that column of a summary row.
 */
@Component
public class RenderPdfStepTask extends RegisteredTask {

    static final int DEFAULT_MAX_ROWS = 1000;
    static final int MAX_ROWS = 5000;
    static final int MAX_SECTIONS = 20;

    static final TaskSpec SPEC = TaskSpec.builder("render_pdf", "Render PDF", TaskKind.OUTPUT)
        .description("Renders the rows as a PDF report (media-service) and keeps it with the run as an output file.")
        .input(TaskSpec.rows("The rows to put in the report."))
        .output(TaskSpec.rows("The same rows, unchanged; the PDF is kept with the run."))
        .config(JsonSchema.object()
            .required("fileName", JsonSchema.string().pattern("^[A-Za-z0-9][A-Za-z0-9._-]{0,123}$").title("File name")
                .description("The report's name; .pdf is added when it does not end with it."))
            .property("title", JsonSchema.string().maxLength(200).format("template").title("Title")
                .description("Above the table; fills from the run: Daily report, run {{run}}."))
            .property("fields", JsonSchema.map(JsonSchema.string().maxLength(500).format("template")).title("Header fields")
                .description("Label and value pairs above the table: Status = DRAFT for review. A label is plain text."))
            .property("columns", JsonSchema.array(JsonSchema.string().format("column")).maxItems(50).title("Columns")
                .description("Which columns, in order; all of them when empty."))
            .property("orientation", JsonSchema.string().enumOf("auto", "portrait", "landscape").title("Page").defaultValue("auto")
                .description("auto turns the page for a table of more than six columns."))
            .property("watermark", JsonSchema.string().maxLength(40).format("template").title("Watermark")
                .description("Printed across every page, e.g. DRAFT."))
            .property("templateId", JsonSchema.integer().minimum(1).title("Report template")
                .description("A report template of this workspace (Documents > Converter); the default layout when empty."))
            .property("maxRows", JsonSchema.integer().minimum(1).maximum(MAX_ROWS).title("At most rows").defaultValue(DEFAULT_MAX_ROWS))
            .property("sections", JsonSchema.array(JsonSchema.object()
                    .required("title", JsonSchema.string().minLength(1).maxLength(200).format("template").title("Section title")
                        .description("Above this table; fills from the run and the step's first row."))
                    .property("input", JsonSchema.string().minLength(1).format("step").title("Rows of")
                        .description("The earlier step whose rows this table shows; this step's input when empty."))
                    .property("columns", JsonSchema.array(JsonSchema.string().format("column")).maxItems(50).title("Columns")
                        .description("Which columns, in order; all of them when empty."))
                    .property("note", JsonSchema.string().maxLength(500).format("template").title("Note")
                        .description("A line under the title, e.g. Rows with a missing value are left out."))
                    .property("maxRows", JsonSchema.integer().minimum(1).maximum(MAX_ROWS).title("At most rows").defaultValue(DEFAULT_MAX_ROWS)))
                .maxItems(MAX_SECTIONS).title("Sections")
                .description("Several tables in one report, each under its own title. Empty: one table of this step's input (Columns "
                    + "and At most rows above). A report template shows them where it says {{sections}}.")))
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
        List<Map<String, Object>> sectionConfigs = Configs.objects(config, "sections");
        long inputRows = context.inputSize();
        // The run's values and the first row's columns (as send_notification fills its title): run values win a clash.
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> first = inputRows > 0 ? context.firstInputRow() : null;
        if (first != null) {
            values.putAll(first);
        }
        values.putAll(Templates.ofRun(context, (int) Math.min(Integer.MAX_VALUE, inputRows)));
        String title = Templates.fill(Configs.text(config, "title", fileName.substring(0, fileName.length() - 4)), values);
        Map<String, String> fields = new LinkedHashMap<>();
        Configs.textMap(config, "fields").forEach((label, template) -> fields.put(label, Templates.fill(template, values)));
        String usageKey = String.format("pipeline#%d#%s#a%d", context.jobQueueId(), context.stepKey(), context.attempt());
        String page = Configs.text(config, "orientation", "auto");
        String watermark = Configs.text(config, "watermark", null);
        String filledWatermark = watermark == null ? null : Templates.fill(watermark, values);
        Long templateId = Configs.longValue(config, "templateId");

        if (sectionConfigs.isEmpty()) {
            Dataset input = context.input();
            Table table = table(input, Configs.texts(config, "columns"), Configs.integer(config, "maxRows", DEFAULT_MAX_ROWS), "The report");
            String orientation = "auto".equals(page) ? (table.columns.size() > PORTRAIT_COLUMNS ? "landscape" : "portrait") : page;
            byte[] pdf = this.renderer.renderPdf(new ReportRenderer.Report(context.tenantId(), usageKey, fileName, title, fields,
                table.columns, table.rows, templateId, orientation, filledWatermark));
            context.keepFile(fileName, pdf, input.size(), table.columns);
            context.recordOutput(RunOutput.file(fileName, "pdf", input.size(), pdf.length).of(pdf));
            context.log(String.format("Rendered %d row(s) as %s (%,d bytes).", input.size(), fileName, pdf.length));
            return StepResult.nothing((long) input.size());
        }

        List<ReportRenderer.Section> sections = new ArrayList<>();
        int widest = 0;
        long total = 0;
        List<String> described = new ArrayList<>();
        for (int i = 0; i < sectionConfigs.size(); i++) {
            Map<String, Object> section = sectionConfigs.get(i);
            String of = Configs.text(section, "input", null);
            Dataset rows = of == null ? context.input() : context.dataset(of);
            String what = String.format("Section %d (%s)", i + 1, of == null ? "this step's input" : "<" + of + ">");
            Table table = table(rows, Configs.texts(section, "columns"), Configs.integer(section, "maxRows", DEFAULT_MAX_ROWS), what);
            String sectionTitle = Templates.fill(Configs.text(section, "title", "Section " + (i + 1)), values);
            String note = Configs.text(section, "note", null);
            sections.add(new ReportRenderer.Section(sectionTitle, note == null ? null : Templates.fill(note, values), table.columns, table.rows));
            widest = Math.max(widest, table.columns.size());
            total += table.rows.size();
            described.add(String.format("%s: %d row(s)", sectionTitle, table.rows.size()));
        }
        String orientation = "auto".equals(page) ? (widest > PORTRAIT_COLUMNS ? "landscape" : "portrait") : page;
        byte[] pdf = this.renderer.renderPdf(new ReportRenderer.Report(context.tenantId(), usageKey, fileName, title, fields,
            new ArrayList<>(), new ArrayList<>(), templateId, orientation, filledWatermark, sections));
        context.keepFile(fileName, pdf, total, new ArrayList<>());
        context.recordOutput(RunOutput.file(fileName, "pdf", total, pdf.length).of(pdf));
        context.log(String.format("Rendered %d section(s), %d row(s) in all, as %s (%,d bytes): %s.", sections.size(), total, fileName,
            pdf.length, String.join("; ", described)));
        return StepResult.nothing(inputRows);
    }

    /** One table: its columns (all, or the named ones in order) and each row's values in that order, held to maxRows. */
    private static Table table(Dataset input, List<String> named, int maxRows, String what) {
        if (input.size() > maxRows) {
            throw new IllegalStateException(String.format("%s would have %d rows; this step renders at most %d (maxRows).", what,
                input.size(), maxRows));
        }
        List<String> columns = named.isEmpty() ? new ArrayList<>(input.getColumns()) : named;
        for (String column : columns) {
            if (!input.getColumns().contains(column)) {
                throw new IllegalStateException("The report".equals(what) ? String.format("The rows have no column '%s'.", column)
                    : String.format("%s: the rows have no column '%s'.", what, column));
            }
        }
        List<List<Object>> rows = new ArrayList<>(input.size());
        for (Map<String, Object> row : input.getRows()) {
            List<Object> values = new ArrayList<>(columns.size());
            for (String column : columns) {
                values.add(row.get(column));
            }
            rows.add(values);
        }
        return new Table(columns, rows);
    }

    private static final class Table {
        final List<String> columns;
        final List<List<Object>> rows;

        Table(List<String> columns, List<List<Object>> rows) {
            this.columns = columns;
            this.rows = rows;
        }
    }
}
