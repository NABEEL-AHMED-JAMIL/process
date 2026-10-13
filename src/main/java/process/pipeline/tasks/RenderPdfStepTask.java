package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.RunOutput;
import process.pipeline.StepReferences;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.backing.ReportLines;
import process.pipeline.backing.ReportRenderer;
import process.pipeline.data.FileFormats;
import process.pipeline.data.RowSource;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Render PDF (MIG-255): turns the rows the step reads into a PDF report and keeps it with the run as a pdf output, as Save
 * File keeps a csv. media-service renders it (a table under a title and header fields, in the workspace's report template
 * when one is named); Core never draws a page. The title and fields fill from the run ({{run}}, {{date}}, {{rows}}...);
 * columns choose which columns and their order (all, by default). The rows pass on unchanged. A retried step bills once:
 * the render's usage key is the run's step and attempt.
 *
 * MIG-346: {@code sections} draws several tables in one report, each under its own title (and an optional note): the rows
 * of an earlier step ({@code input}) or of this step's input, its own columns and maxRows. The title, header fields, notes
 * and watermark fill from the run and from the first row of the step's input ({{column}}).
 *
 * ADR-027 (big-data PDFs). A PDF is for people, so it never fails because the data is big:
 * <ul>
 *   <li>The step streams: it reads its input (and each section's) a batch at a time, never the whole table.</li>
 *   <li>A table prints its first maxRows rows (default {@value #DEFAULT_MAX_ROWS}); under it the report says how many more
 *       rows the data has and -- with attachFullData (on by default) -- that the full data is in a CSV kept beside the PDF
 *       as a run output of its own, written as the rows stream past.</li>
 *   <li>A statistics section ({@code kind: statistics}) profiles every row of its input as it streams: rows, empty, distinct,
 *       min, max, sum, mean and the most frequent values per column (ReportStatistics), and a histogram per numeric
 *       column named in {@code histogram}.</li>
 *   <li>The report goes to media-service as lines written to the step's scratch directory (ReportLines), and the PDF comes
 *       back as a stream written straight to the run's file area: no JSON body of every row, no base64.</li>
 * </ul>
 */
@Component
public class RenderPdfStepTask extends RegisteredTask implements StreamingStepTask {

    static final int DEFAULT_MAX_ROWS = 5000;
    static final int MAX_ROWS = 50_000;
    static final int MAX_SECTIONS = 20;
    static final int DEFAULT_TOP_VALUES = 5;
    static final int MAX_HISTOGRAMS = 10;

    static final TaskSpec SPEC = TaskSpec.builder("render_pdf", "Render PDF", TaskKind.OUTPUT)
        .description("Renders the rows as a PDF report (media-service) and keeps it with the run as an output file. A big table "
            + "prints its first rows and keeps the full data as a CSV beside the PDF.")
        .input(TaskSpec.rows("The rows to put in the report."))
        .output(TaskSpec.rows("The same rows, unchanged; the PDF (and the full data, when a table was cut) is kept with the run."))
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
                .description("auto turns the page for a table of more than six columns. A table too wide for the page is "
                    + "printed in page sets, each repeating the first column."))
            .property("watermark", JsonSchema.string().maxLength(40).format("template").title("Watermark")
                .description("Printed across every page, e.g. DRAFT."))
            .property("templateId", JsonSchema.integer().minimum(1).title("Report template").format(StepReferences.REPORT_TEMPLATE)
                .description("A report template of this workspace (Documents > Converter); the default layout when empty."))
            .property("maxRows", JsonSchema.integer().minimum(1).maximum(MAX_ROWS).title("Rows in the PDF").defaultValue(DEFAULT_MAX_ROWS)
                .description("A table prints at most this many rows; past it the report says how many more there are."))
            .property("attachFullData", JsonSchema.bool().title("Attach the full data").defaultValue(true)
                .description("When a table has more rows than the PDF prints, keep all of them as a CSV beside the PDF."))
            .property("sections", JsonSchema.array(JsonSchema.object()
                    .required("title", JsonSchema.string().minLength(1).maxLength(200).format("template").title("Section title")
                        .description("Above this table; fills from the run and the step's first row."))
                    .property("kind", JsonSchema.string().enumOf("table", "statistics").title("Shows").defaultValue("table")
                        .description("table: the rows. statistics: per column, over every row -- rows, empty, distinct, min, max, "
                            + "sum, mean and the most frequent values."))
                    .property("input", JsonSchema.string().minLength(1).format("step").title("Rows of")
                        .description("The earlier step whose rows this section shows; this step's input when empty."))
                    .property("columns", JsonSchema.array(JsonSchema.string().format("column")).maxItems(200).title("Columns")
                        .description("Which columns, in order; all of them when empty."))
                    .property("note", JsonSchema.string().maxLength(500).format("template").title("Note")
                        .description("A line under the title, e.g. Rows with a missing value are left out."))
                    .property("maxRows", JsonSchema.integer().minimum(1).maximum(MAX_ROWS).title("Rows in the PDF")
                        .defaultValue(DEFAULT_MAX_ROWS))
                    .property("topValues", JsonSchema.integer().minimum(0).maximum(20).title("Most frequent values")
                        .defaultValue(DEFAULT_TOP_VALUES).description("statistics: how many of each column's most frequent values."))
                    .property("histogram", JsonSchema.array(JsonSchema.string().format("column")).maxItems(MAX_HISTOGRAMS)
                        .title("Histograms").description("statistics: numeric columns drawn as ten equal-width bins.")))
                .maxItems(MAX_SECTIONS).title("Sections")
                .description("Several tables in one report, each under its own title. Empty: one table of this step's input (Columns "
                    + "and Rows in the PDF above). A report template shows them where it says {{sections}}.")))
        .backing(TaskSpec.MEDIA)
        .timeoutSeconds(900)
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
        List<Map<String, Object>> sections = Configs.objects(config, "sections");
        for (int i = 0; i < sections.size(); i++) {
            Map<String, Object> section = sections.get(i);
            boolean statistics = "statistics".equals(Configs.text(section, "kind", "table"));
            if (!statistics && !Configs.texts(section, "histogram").isEmpty()) {
                problems.add(new DefinitionProblem("sections." + i + ".histogram", "a histogram belongs to a statistics section"));
            }
        }
        return problems;
    }

    /** One section as configured, or the plain report's one table. */
    private static final class Plan {
        String title;
        String note;
        String input;
        List<String> columns;
        int maxRows;
        boolean statistics;
        int topValues;
        List<String> histogram;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        String fileName = Configs.text(config, "fileName", "report");
        if (!fileName.toLowerCase().endsWith(".pdf")) {
            fileName = fileName + ".pdf";
        }
        String base = fileName.substring(0, fileName.length() - 4);
        long inputRows = context.inputSize();
        // The run's values and the first row's columns (as send_notification fills its title): run values win a clash.
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> first = inputRows > 0 ? context.firstInputRow() : null;
        if (first != null) {
            values.putAll(first);
        }
        values.putAll(Templates.ofRun(context, (int) Math.min(Integer.MAX_VALUE, inputRows)));
        String title = Templates.fill(Configs.text(config, "title", base), values);
        Map<String, String> fields = new LinkedHashMap<>();
        Configs.textMap(config, "fields").forEach((label, template) -> fields.put(label, Templates.fill(template, values)));
        String usageKey = String.format("pipeline#%d#%s#a%d", context.jobQueueId(), context.stepKey(), context.attempt());
        String page = Configs.text(config, "orientation", "auto");
        String watermark = Configs.text(config, "watermark", null);
        boolean attach = Configs.bool(config, "attachFullData", true);

        List<Plan> plans = plans(config, values);
        boolean plain = Configs.objects(config, "sections").isEmpty();
        List<String> described = new ArrayList<>();
        List<RunOutput> dataFiles = new ArrayList<>();
        /** The full data already kept for an input (two tables of one input share one CSV). */
        Map<String, String> keptFor = new HashMap<>();
        int widest = 0;
        long printed = 0;
        try (ReportLines lines = new ReportLines(context.scratch())) {
            for (int i = 0; i < plans.size(); i++) {
                Plan plan = plans.get(i);
                String what = plain ? "The report" : String.format("Section %d (%s)", i + 1,
                    plan.input == null ? "this step's input" : "<" + plan.input + ">");
                if (plan.statistics) {
                    widest = Math.max(widest, ReportStatistics.HEADER.size());
                    described.add(this.statistics(context, plan, what, lines));
                    continue;
                }
                try (RowSource rows = open(context, plan.input)) {
                    List<String> columns = columnsOf(rows, plan.columns, what, plain);
                    widest = Math.max(widest, columns.size());
                    long total = rows.size();
                    long shown = Math.min(total, plan.maxRows);
                    String dataFile = null;
                    String key = plan.input == null ? "" : plan.input;
                    if (total > shown && attach) {
                        dataFile = keptFor.get(key);
                        if (dataFile == null) {
                            dataFile = dataFileName(base, plain ? null : (plan.input == null ? "input" : plan.input), keptFor.values());
                        }
                    }
                    String trailer = trailer(total, shown, dataFile);
                    lines.section(plain ? null : plan.title, plan.note, columns, shown, trailer);
                    if (dataFile != null && !keptFor.containsKey(key)) {
                        // One pass: every row to the CSV, the first ones also to the report.
                        Tee tee = new Tee(rows, lines, columns, shown);
                        StreamContext.KeptFile kept = context.keepFile(dataFile, rows.columns(), total,
                            out -> FileFormats.writeTo(tee, "csv", out));
                        dataFiles.add(RunOutput.file(dataFile, "csv", total, kept.bytes).withSha256(kept.sha256));
                        keptFor.put(key, dataFile);
                    } else {
                        copy(rows, lines, columns, shown);
                    }
                    printed += shown;
                    described.add(String.format("%s: %,d of %,d row(s)%s", plain ? "table" : plan.title, shown, total,
                        dataFile == null ? "" : ", full data in " + dataFile));
                }
            }
            String orientation = "auto".equals(page) ? (widest > PORTRAIT_COLUMNS ? "landscape" : "portrait") : page;
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("title", title);
            head.put("fields", fields);
            head.put("orientation", orientation);
            head.put("watermark", watermark == null ? null : Templates.fill(watermark, values));
            head.put("templateId", Configs.longValue(config, "templateId"));
            head.put("fileName", fileName);
            head.put("keyColumns", 1);
            lines.head(head);

            ReportRenderer.Rendered[] rendered = new ReportRenderer.Rendered[1];
            StreamContext.KeptFile pdf = context.keepFile(fileName, new ArrayList<>(), printed,
                out -> rendered[0] = this.renderer.render(context.tenantId(), usageKey, lines, out));
            context.recordOutput(RunOutput.file(fileName, "pdf", printed, pdf.bytes).withSha256(pdf.sha256).withPages(rendered[0].pages));
            for (RunOutput data : dataFiles) {
                context.recordOutput(data);
            }
            context.log(String.format("Rendered %s as %s: %,d page(s), %,d bytes. %s.", plans.size() == 1 ? "1 table" : plans.size()
                + " sections", fileName, rendered[0].pages, pdf.bytes, String.join("; ", described)));
        }
        return StepResult.nothing(inputRows);
    }

    private static List<Plan> plans(Map<String, Object> config, Map<String, Object> values) {
        List<Plan> plans = new ArrayList<>();
        List<Map<String, Object>> sections = Configs.objects(config, "sections");
        if (sections.isEmpty()) {
            Plan plan = new Plan();
            plan.columns = Configs.texts(config, "columns");
            plan.maxRows = Configs.integer(config, "maxRows", DEFAULT_MAX_ROWS);
            plans.add(plan);
            return plans;
        }
        for (int i = 0; i < sections.size(); i++) {
            Map<String, Object> section = sections.get(i);
            Plan plan = new Plan();
            plan.title = Templates.fill(Configs.text(section, "title", "Section " + (i + 1)), values);
            String note = Configs.text(section, "note", null);
            plan.note = note == null ? null : Templates.fill(note, values);
            plan.input = Configs.text(section, "input", null);
            plan.columns = Configs.texts(section, "columns");
            plan.maxRows = Configs.integer(section, "maxRows", DEFAULT_MAX_ROWS);
            plan.statistics = "statistics".equals(Configs.text(section, "kind", "table"));
            plan.topValues = Configs.integer(section, "topValues", DEFAULT_TOP_VALUES);
            plan.histogram = Configs.texts(section, "histogram");
            plans.add(plan);
        }
        return plans;
    }

    /** A statistics section: every row of its input profiled as it streams, then a second pass for any histogram. */
    private String statistics(StreamContext context, Plan plan, String what, ReportLines lines) throws Exception {
        ReportStatistics stats;
        long total;
        try (RowSource rows = open(context, plan.input)) {
            List<String> columns = columnsOf(rows, plan.columns, what, false);
            stats = new ReportStatistics(columns, context.memory(), plan.topValues);
            total = rows.size();
            for (List<Map<String, Object>> batch = rows.next(); batch != null; batch = rows.next()) {
                for (Map<String, Object> row : batch) {
                    stats.add(row);
                }
            }
        }
        try {
            List<List<Object>> table = stats.rows();
            String note = plan.note == null ? String.format("Every one of the %,d rows, column by column.", total) : plan.note;
            lines.section(plan.title, note, ReportStatistics.HEADER, table.size(), null);
            for (List<Object> row : table) {
                lines.row(row);
            }
            List<ReportStatistics.Histogram> histograms = new ArrayList<>();
            for (String column : plan.histogram) {
                ReportStatistics.Column profile = stats.column(column);
                if (profile == null) {
                    throw new IllegalStateException(String.format("%s: a histogram of '%s', which the section does not profile.", what, column));
                }
                if (!profile.numeric || profile.min == null) {
                    context.warn(String.format("%s: '%s' is not numeric, so it has no histogram.", what, column));
                    continue;
                }
                histograms.add(new ReportStatistics.Histogram(column, profile.min, profile.max));
            }
            if (!histograms.isEmpty()) {
                try (RowSource rows = open(context, plan.input)) {
                    for (List<Map<String, Object>> batch = rows.next(); batch != null; batch = rows.next()) {
                        for (Map<String, Object> row : batch) {
                            for (ReportStatistics.Histogram histogram : histograms) {
                                histogram.add(row.get(histogram.column));
                            }
                        }
                    }
                }
                for (ReportStatistics.Histogram histogram : histograms) {
                    List<List<Object>> bins = histogram.rows();
                    lines.section(plan.title + ": " + histogram.column, null, ReportStatistics.Histogram.HEADER, bins.size(), null);
                    for (List<Object> bin : bins) {
                        lines.row(bin);
                    }
                }
            }
            return String.format("%s: statistics of %,d row(s) x %d column(s)%s", plan.title, total, table.size(),
                histograms.isEmpty() ? "" : ", " + histograms.size() + " histogram(s)");
        } finally {
            stats.release();
        }
    }

    private static RowSource open(StreamContext context, String input) throws Exception {
        return input == null ? context.openInput() : context.openOutputOf(input);
    }

    private static List<String> columnsOf(RowSource rows, List<String> named, String what, boolean plain) {
        List<String> columns = named.isEmpty() ? new ArrayList<>(rows.columns()) : named;
        for (String column : columns) {
            if (!rows.columns().contains(column)) {
                throw new IllegalStateException(plain ? String.format("The rows have no column '%s'.", column)
                    : String.format("%s: the rows have no column '%s'.", what, column));
            }
        }
        return columns;
    }

    /** Under a table: all its rows, or how many more there are and where they are. */
    static String trailer(long total, long shown, String dataFile) {
        if (total <= shown) {
            return total == 0 ? null : String.format("%,d row%s.", total, total == 1 ? "" : "s");
        }
        return String.format("Showing the first %,d of %,d rows. %,d more row%s: %s.", shown, total, total - shown,
            total - shown == 1 ? "" : "s", dataFile == null ? "the full data is the step's input in this run"
                : "the full data is in " + dataFile + ", kept with this report");
    }

    /** report.pdf -> report-data.csv; with sections, the input's key too (report-input-data.csv), never a name taken. */
    static String dataFileName(String base, String input, Collection<String> taken) {
        String stem = input == null ? base + "-data" : base + "-" + input.replaceAll("[^A-Za-z0-9._-]", "_") + "-data";
        if (stem.length() > 120) {
            stem = stem.substring(0, 120);
        }
        String name = stem + ".csv";
        for (int n = 2; taken.contains(name); n++) {
            name = stem + "-" + n + ".csv";
        }
        return name;
    }

    private static void copy(RowSource rows, ReportLines lines, List<String> columns, long shown) throws Exception {
        long written = 0;
        while (written < shown) {
            List<Map<String, Object>> batch = rows.next();
            if (batch == null) {
                break;
            }
            for (Map<String, Object> row : batch) {
                if (written == shown) {
                    break;
                }
                lines.row(row, columns);
                written++;
            }
        }
    }

    /** The input passed on to the CSV whole, its first {@code shown} rows also written to the report. */
    private static final class Tee implements RowSource {
        private final RowSource source;
        private final ReportLines lines;
        private final List<String> columns;
        private final long shown;
        private long written;

        Tee(RowSource source, ReportLines lines, List<String> columns, long shown) {
            this.source = source;
            this.lines = lines;
            this.columns = columns;
            this.shown = shown;
        }

        @Override
        public List<String> columns() {
            return this.source.columns();
        }

        @Override
        public long size() {
            return this.source.size();
        }

        @Override
        public List<Map<String, Object>> next() throws Exception {
            List<Map<String, Object>> batch = this.source.next();
            if (batch != null && this.written < this.shown) {
                for (Map<String, Object> row : batch) {
                    if (this.written == this.shown) {
                        break;
                    }
                    this.lines.row(row, this.columns);
                    this.written++;
                }
            }
            return batch;
        }

        @Override
        public void close() throws Exception {
            // the caller closes the source
        }
    }
}
