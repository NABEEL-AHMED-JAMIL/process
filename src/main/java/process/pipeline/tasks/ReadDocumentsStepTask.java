package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.RunDocuments;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.backing.DocumentDatasets;
import process.pipeline.backing.DocumentReadMarks;
import process.pipeline.backing.ReadTakes;
import org.springframework.beans.factory.annotation.Autowired;
import process.pipeline.data.Limits;
import process.pipeline.data.Values;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read documents (MIG-364): a Document Intelligence document type's documents as rows, through ai-service's trusted read
 * -- the run's workspace's own documents only, a page at a time (a dataset of any size streams; the step holds one page).
 *
 * <ul>
 *   <li>With a document type: its <b>approved</b> dataset -- the values each approval froze -- optionally only the
 *       documents approved <b>since the last run</b> (a watermark per job and step: after the highest dataset row a
 *       completed run of this job read; a failed run moves nothing), and/or in a <b>date range</b> of approval; and, by
 *       <b>status</b>, the documents waiting in the review queue or rejected, with the values they have now.</li>
 *   <li>With no type: the documents whose approval started this run (a job's "When a document is approved" trigger) --
 *       one, or the batch that waited -- as read_file with no file reads the run's own file.</li>
 * </ul>
 *
 * Each row: the document's {@code extraction_id} (what the review screen opens), {@code dataset_row_id},
 * {@code type_version}, {@code status}, {@code approval} (auto or reviewer), {@code approved_by}, {@code date_approved}
 * (the platform's wall clock, America/Chicago), {@code file_name}, {@code rules_failed} (the rules it failed, by name) --
 * and, reading review or rejected documents, {@code reject_reason} and {@code date_decided} -- then the type's fields in
 * its order, then each table as JSON text; or, with {@code tableRows}, one row per row of that table with the document's
 * values beside it. A field named like one of those columns keeps its name and the column becomes {@code _<name>}; a
 * table column named like a field becomes {@code <table>_<name>}. {@code columns} keeps only the columns named, in that
 * order. Billed by ai-service as a trusted read (one read op and the bytes per page).
 */
@Component
public class ReadDocumentsStepTask extends RegisteredTask implements StreamingStepTask {

    static final String CODE = "read_documents";
    static final int PAGE = 500;
    static final String SINCE_ALL = "all";
    static final String SINCE_LAST_RUN = "last_run";

    /** The document's own columns, before its fields. */
    static final List<String> META = Collections.unmodifiableList(Arrays.asList("extraction_id", "dataset_row_id", "type_version", "status",
        "approval", "approved_by", "date_approved", "file_name", "rules_failed"));
    /** Added when review or rejected documents are read. */
    static final List<String> DECIDED = Collections.unmodifiableList(Arrays.asList("reject_reason", "date_decided"));

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2})?)?");

    static final TaskSpec SPEC = TaskSpec.builder(CODE, "Read documents", TaskKind.READ)
        .description("Reads a document type's approved documents (Document Intelligence) as rows -- all, since the last run, or in a date "
            + "range; or the documents whose approval started the run.")
        .output(TaskSpec.rows("One row per document (or per row of a table): its id, status, approval and the type's fields."))
        .config(JsonSchema.object()
            .property("documentTypeId", JsonSchema.integer().minimum(1).format("document-type").title("Document type")
                .description("The type whose documents to read (Documents > Types). Empty: the documents whose approval started this run."))
            .property("statuses", JsonSchema.array(JsonSchema.string().enumOf(DocumentDatasets.APPROVED, DocumentDatasets.REVIEW,
                    DocumentDatasets.REJECTED)).minItems(1).maxItems(3).title("Documents")
                .description("Approved (the type's dataset), Review (waiting in the review queue) and/or Rejected; Approved when empty."))
            .property("since", JsonSchema.string().enumOf(SINCE_ALL, SINCE_LAST_RUN).title("Which approved documents").defaultValue(SINCE_ALL)
                .description("all, or only those approved since this job's last completed run (a watermark per job and step)."))
            .property("from", JsonSchema.string().maxLength(64).format("template").title("Approved from")
                .description("A date (2026-10-01) or date and time, platform time; {{date}} and the other run placeholders are filled in."))
            .property("to", JsonSchema.string().maxLength(64).format("template").title("Approved to")
                .description("The last day (included), or a date and time (excluded)."))
            .property("tableRows", JsonSchema.string().minLength(1).maxLength(64).title("One row per row of table")
                .description("A table of the type (line_items): one row per table row, the document's values beside it. Empty: each "
                    + "table as JSON text in one column."))
            .property("columns", JsonSchema.array(JsonSchema.string().minLength(1).maxLength(255).format("column")).maxItems(Limits.MAX_COLUMNS)
                .title("Only these columns").description("Keep only these columns, in this order; every column when empty."))
            .property("maxRows", JsonSchema.integer().minimum(1).title("At most rows").description("Stop after this many rows.")))
        .backing(TaskSpec.AI)
        .retry(3, 10)
        .timeoutSeconds(900)
        .aiToolName(CODE)
        .build();

    private final DocumentDatasets documents;
    private final DocumentReadMarks marks;
    private ReadTakes takes;

    public ReadDocumentsStepTask(DocumentDatasets documents, DocumentReadMarks marks) {
        super(SPEC);
        this.documents = documents;
        this.marks = marks;
    }

    /** Owner 2026-10-21: which run took which document, so runs side by side (a workflow's requests) never read the same one. */
    @Autowired(required = false)
    public void useTakes(ReadTakes takes) {
        this.takes = takes;
    }

    @Override
    public Optional<String> unavailable() {
        return this.documents.unavailable();
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Long type = Configs.longValue(config, "documentTypeId");
        List<String> statuses = statuses(config);
        boolean lastRun = SINCE_LAST_RUN.equals(Configs.text(config, "since", SINCE_ALL));
        if (lastRun && type == null) {
            problems.add(new DefinitionProblem("since", "since the last run needs the document type"));
        }
        if (lastRun && !statuses.equals(Collections.singletonList(DocumentDatasets.APPROVED))) {
            problems.add(new DefinitionProblem("since", "since the last run reads approved documents only"));
        }
        if (type == null && !statuses.equals(Collections.singletonList(DocumentDatasets.APPROVED))) {
            problems.add(new DefinitionProblem("statuses", "the documents that started the run are approved ones; choose a type to read others"));
        }
        for (String bound : new String[] {"from", "to"}) {
            String value = Configs.text(config, bound, null);
            if (value != null && !value.contains("{{") && !DATE.matcher(value.trim()).matches()) {
                problems.add(new DefinitionProblem(bound, "a date (2026-10-01) or a date and time (2026-10-01T08:30)"));
            }
        }
        Set<String> seen = new HashSet<>();
        List<String> columns = Configs.texts(config, "columns");
        for (int i = 0; i < columns.size(); i++) {
            if (!seen.add(columns.get(i))) {
                problems.add(new DefinitionProblem("columns[" + i + "]", String.format("'%s' is named twice", columns.get(i))));
            }
        }
        return problems;
    }

    static List<String> statuses(Map<String, Object> config) {
        List<String> statuses = new ArrayList<>(new LinkedHashSet<>(Configs.texts(config, "statuses")));
        return statuses.isEmpty() ? Collections.singletonList(DocumentDatasets.APPROVED) : statuses;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        Map<String, Object> run = Templates.ofRun(context, null);
        Long type = Configs.longValue(config, "documentTypeId");
        List<String> statuses = statuses(config);
        boolean lastRun = SINCE_LAST_RUN.equals(Configs.text(config, "since", SINCE_ALL));
        String from = Templates.fill(Configs.text(config, "from", null), run);
        String to = Templates.fill(Configs.text(config, "to", null), run);
        Integer maxRows = Configs.integer(config, "maxRows", null);
        List<String> wanted = Configs.texts(config, "columns");
        String tableRows = Configs.text(config, "tableRows", null);

        List<Long> named = null;
        if (type == null) {
            List<RunDocuments.Document> started = context.documents();
            if (started.isEmpty()) {
                throw new IllegalStateException("This run was not started by an approved document; choose the document type to read.");
            }
            type = started.get(0).documentTypeId;
            named = new ArrayList<>();
            for (RunDocuments.Document d : started) {
                if (d.documentTypeId == type) {
                    named.add(d.extractionId);
                }
            }
            context.log(String.format("Reading the %d approved document(s) this run was started for (type %d).", named.size(), type));
        }

        long watermark = 0;
        Long jobId = context.jobId();
        if (lastRun) {
            if (jobId == null || this.marks == null) {
                throw new IllegalStateException("Since the last run needs the run's job; run the step in a job.");
            }
            watermark = this.marks.watermark(jobId, context.stepKey(), type);
            context.log(watermark == 0 ? "No earlier completed run read this type: reading every approved document."
                : String.format("Reading the documents approved since the last completed run (after dataset row %d).", watermark));
        }
        // Runs side by side (a workflow's requests) start from the same watermark: each takes the documents no run in flight
        // took, and a failed run's documents -- behind the watermark or not -- come again. A run started for its own
        // documents reads them, taken or not.
        String takeSource = "documents|" + type;
        boolean taking = lastRun && named == null && this.takes != null;
        long readFrom = watermark;
        if (taking) {
            for (String again : this.takes.released(jobId, context.stepKey(), takeSource)) {
                readFrom = Math.min(readFrom, Long.parseLong(again) - 1);
            }
            if (readFrom < watermark) {
                context.log(String.format("Documents a run that did not complete had taken are read again (from dataset row %d).", readFrom + 1));
            }
        }
        int heldByOthers = 0;

        boolean decided = statuses.contains(DocumentDatasets.REVIEW) || statuses.contains(DocumentDatasets.REJECTED);
        Shape shape = null;
        long upTo = watermark;
        long documents = 0;
        int pages = 0;
        boolean names = wanted.isEmpty() || wanted.contains("file_name") || wanted.contains("_file_name");
        reading:
        for (String status : statuses) {
            DocumentDatasets.Request request = new DocumentDatasets.Request();
            request.tenantId = context.tenantId();
            request.documentTypeId = type;
            request.status = status;
            request.after = DocumentDatasets.APPROVED.equals(status) ? readFrom : 0;
            request.from = from;
            request.to = to;
            request.extractionIds = named;
            request.limit = PAGE;
            request.names = names;
            request.jobQueueId = context.jobQueueId();
            request.readKey = String.format("%d#%d#%s#%d", context.jobQueueId(), context.attempt(), context.stepKey(), context.tryNumber());
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("The read was stopped.");
                }
                DocumentDatasets.Page page = this.documents.read(request);
                pages++;
                if (shape == null) {
                    shape = Shape.of(page, decided, tableRows, wanted);
                    if (shape.tableMissing) {
                        throw new IllegalArgumentException(String.format("The type has no table '%s'; its tables: %s.", tableRows,
                            shape.tables.isEmpty() ? "none" : String.join(", ", shape.tables)));
                    }
                    context.output().declare(shape.output);
                }
                Set<String> mine = null;
                if (taking && DocumentDatasets.APPROVED.equals(status)) {
                    List<String> onPage = new ArrayList<>();
                    Set<String> fresh = new HashSet<>();
                    for (JsonNode row : page.rows) {
                        if (row.hasNonNull("datasetRowId")) {
                            onPage.add(row.get("datasetRowId").asText());
                            if (row.get("datasetRowId").asLong() > watermark) {
                                fresh.add(row.get("datasetRowId").asText());
                            }
                        }
                    }
                    ReadTakes.Taken took = this.takes.take(context.jobQueueId(), jobId, context.stepKey(), takeSource, onPage, fresh,
                        Integer.MAX_VALUE);
                    mine = new HashSet<>(took.items);
                    heldByOthers += took.heldByOthers;
                }
                for (JsonNode row : page.rows) {
                    if (mine != null && !(row.hasNonNull("datasetRowId") && mine.contains(row.get("datasetRowId").asText()))) {
                        continue;  // another run in flight took it, or it is behind the watermark and was read
                    }
                    documents++;
                    if (DocumentDatasets.APPROVED.equals(status) && row.hasNonNull("datasetRowId")) {
                        upTo = Math.max(upTo, row.get("datasetRowId").asLong());
                    }
                    for (Map<String, Object> out : shape.rows(row)) {
                        if (maxRows != null && context.output().size() >= maxRows) {
                            break reading;
                        }
                        if (context.output().size() >= context.maxRows()) {
                            throw new IllegalStateException(String.format("More than %,d rows; narrow the read (a date range, since the last "
                                + "run) or set At most rows.", context.maxRows()));
                        }
                        context.output().add(out);
                    }
                }
                if (page.next == null) {
                    break;
                }
                request.after = page.next;
            }
        }
        if (heldByOthers > 0) {
            context.log(String.format("%d approved document(s) are being read by another run of this job; they are left to it.", heldByOthers));
        }
        if (lastRun) {
            this.marks.record(context.jobQueueId(), context.stepKey(), jobId, type, upTo, documents, context.attempt());
        }
        context.log(String.format("%d document(s) of type %d (%s), %d row(s), in %d page(s)%s.", documents, type,
            shape == null || shape.typeKey == null ? "?" : shape.typeKey, context.output().size(), pages,
            lastRun ? String.format("; the next run reads after dataset row %d", upTo) : ""));
        return StepResult.streamed(context.output().size());
    }

    /** The output's columns, from the type's (every version's), and how a document becomes its row(s). */
    static final class Shape {
        final String typeKey;
        final List<String> fields;
        final List<String> tables;
        final String table;
        final boolean tableMissing;
        final List<String> tableColumns;
        /** Column -> where its value comes from: "m:<meta>", "f:<field>", "t:<table>" (JSON), "r:<column>" (a table row's). */
        final Map<String, String> source = new LinkedHashMap<>();
        final List<String> output;

        private Shape(DocumentDatasets.Page page, boolean decided, String table, List<String> wanted) {
            this.typeKey = page.typeKey;
            this.fields = page.fields;
            this.tables = page.tables;
            this.table = table;
            this.tableMissing = table != null && !page.tables.contains(table);
            this.tableColumns = table == null || this.tableMissing ? Collections.emptyList()
                : page.tableColumns.getOrDefault(table, Collections.emptyList());
            Set<String> fieldNames = new HashSet<>(page.fields);
            List<String> meta = new ArrayList<>(META);
            if (decided) {
                meta.addAll(DECIDED);
            }
            for (String m : meta) {
                this.source.put(fieldNames.contains(m) ? "_" + m : m, "m:" + m);
            }
            for (String f : page.fields) {
                this.source.put(f, "f:" + f);
            }
            if (table == null) {
                for (String t : page.tables) {
                    this.source.putIfAbsent(t, "t:" + t);
                }
            } else {
                for (String c : this.tableColumns) {
                    this.source.put(this.source.containsKey(c) ? table + "_" + c : c, "r:" + c);
                }
            }
            if (wanted.isEmpty()) {
                this.output = new ArrayList<>(this.source.keySet());
            } else {
                List<String> missing = new ArrayList<>();
                for (String w : wanted) {
                    if (!this.source.containsKey(w)) {
                        missing.add(w);
                    }
                }
                if (!missing.isEmpty()) {
                    throw new IllegalArgumentException(String.format("The documents have no column %s; they have: %s.", String.join(", ", missing),
                        String.join(", ", this.source.keySet())));
                }
                this.output = new ArrayList<>(wanted);
            }
        }

        static Shape of(DocumentDatasets.Page page, boolean decided, String table, List<String> wanted) {
            return new Shape(page, decided, table, wanted);
        }

        /** One document as its row, or as one row per row of the table (none when the table is empty: the document is kept, its table cells empty). */
        List<Map<String, Object>> rows(JsonNode document) {
            if (this.table == null || this.tableMissing) {
                return Collections.singletonList(this.row(document, null));
            }
            JsonNode lines = document.path("tables").path(this.table);
            if (!lines.isArray() || lines.size() == 0) {
                return Collections.singletonList(this.row(document, null));
            }
            List<Map<String, Object>> rows = new ArrayList<>(lines.size());
            for (JsonNode line : lines) {
                rows.add(this.row(document, line));
            }
            return rows;
        }

        private Map<String, Object> row(JsonNode d, JsonNode line) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (String column : this.output) {
                String from = this.source.get(column);
                String name = from.substring(2);
                switch (from.charAt(0)) {
                    case 'm':
                        row.put(column, meta(d, name));
                        break;
                    case 'f':
                        row.put(column, Values.scalar(d.path("fields").get(name)));
                        break;
                    case 't':
                        JsonNode t = d.path("tables").get(name);
                        row.put(column, t == null || t.isNull() ? null : Values.scalar(t));
                        break;
                    default:
                        row.put(column, line == null ? null : Values.scalar(line.get(name)));
                }
            }
            return row;
        }

        private static Object meta(JsonNode d, String name) {
            switch (name) {
                case "extraction_id":
                    return Values.scalar(d.get("extractionId"));
                case "dataset_row_id":
                    return Values.scalar(d.get("datasetRowId"));
                case "type_version":
                    return Values.scalar(d.get("typeVersion"));
                case "status":
                    return Values.scalar(d.get("status"));
                case "approval":
                    return Values.scalar(d.get("approval"));
                case "approved_by":
                    return Values.scalar(d.get("approvedBy"));
                case "date_approved":
                    return Values.scalar(d.get("dateApproved"));
                case "file_name":
                    return Values.scalar(d.get("fileName"));
                case "rules_failed":
                    List<String> failed = new ArrayList<>();
                    Iterator<JsonNode> rules = d.path("rulesFailed").elements();
                    rules.forEachRemaining(r -> failed.add(r.asText()));
                    return String.join(", ", failed);
                case "reject_reason":
                    return Values.scalar(d.get("rejectReason"));
                case "date_decided":
                    return Values.scalar(d.get("dateDecided"));
                default:
                    return null;
            }
        }
    }
}
