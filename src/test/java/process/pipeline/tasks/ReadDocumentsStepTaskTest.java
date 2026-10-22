package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.RunDocuments;
import process.pipeline.StepResult;
import process.pipeline.backing.DocumentDatasets;
import process.pipeline.backing.Fakes;
import process.pipeline.data.RunMemory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-364: Read documents -- a document type's approved dataset as rows, the run's workspace's own only, streamed a page
 * at a time; since the last completed run (the watermark), a table's rows, chosen columns; the documents that started
 * the run; and what its settings may not say.
 */
class ReadDocumentsStepTaskTest {

    static final long A = 2961L;
    static final long B = 2962L;
    static final long TYPE = 1007L;

    private final Fakes.Documents documents = new Fakes.Documents();
    private final ReadDocumentsStepTask task = new ReadDocumentsStepTask(this.documents, this.documents);

    @TempDir
    Path dir;

    private StreamTry run(Map<String, Object> config, long runId) {
        StreamTry t = new StreamTry(config, null, RunMemory.DEFAULT_MB * 1024L * 1024L, this.dir, 1024);
        t.tenant = A;
        t.jobId = 77L;
        t.runId = runId;
        return t;
    }

    private static Map<String, Object> config(Object... pairs) {
        Map<String, Object> config = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            config.put((String) pairs[i], pairs[i + 1]);
        }
        return config;
    }

    @Test
    void theTypesApprovedDatasetIsReadAsRowsOfTheWorkspacesOwnDocuments() throws Exception {
        this.documents.approve(A, TYPE, "INV-1", "V-1", 120.5, 2);
        this.documents.approve(B, TYPE, "SECRET-B", "V-9", 999, 1);
        this.documents.approve(A, TYPE, "INV-2", "V-2", 80, 0);
        StreamTry t = this.run(config("documentTypeId", TYPE), 501);

        StepResult result = this.task.stream(t);

        assertThat(result.getRecordsOut()).isEqualTo(2L);
        assertThat(t.declared).containsExactly("extraction_id", "dataset_row_id", "type_version", "status", "approval", "approved_by",
            "date_approved", "file_name", "rules_failed", "invoice_number", "vendor_id", "total", "line_items");
        assertThat(t.rows).extracting(r -> r.get("invoice_number")).containsExactly("INV-1", "INV-2");
        Map<String, Object> first = t.rows.get(0);
        assertThat(first).containsEntry("status", "Approved").containsEntry("file_name", "INV-1.pdf").containsEntry("total", 120.5)
            .containsEntry("type_version", 1L).containsEntry("rules_failed", "");
        assertThat((String) first.get("line_items")).startsWith("[{\"sku\":\"SKU-0\"");
        // Asked as A, billed as the run's read: a key per run, attempt, step and try.
        assertThat(this.documents.requests).allSatisfy(r -> {
            assertThat(r.tenantId).isEqualTo(A);
            assertThat(r.jobQueueId).isEqualTo(501L);
            assertThat(r.readKey).isEqualTo("501#1#step#1");
            assertThat(r.names).isTrue();
        });
    }

    @Test
    void sinceTheLastRunReadsOnlyWhatACompletedRunHasNotRead() throws Exception {
        for (int i = 1; i <= 3; i++) {
            this.documents.approve(A, TYPE, "INV-" + i, "V-1", 10 * i, 1);
        }
        Map<String, Object> since = config("documentTypeId", TYPE, "since", "last_run");

        StreamTry first = this.run(since, 601);
        this.task.stream(first);
        assertThat(first.rows).extracting(r -> r.get("invoice_number")).containsExactly("INV-1", "INV-2", "INV-3");
        this.documents.completed.add(601L);

        long fourth = this.documents.approve(A, TYPE, "INV-4", "V-1", 40, 1);
        this.documents.approve(A, TYPE, "INV-5", "V-1", 50, 1);
        StreamTry second = this.run(since, 602);
        this.task.stream(second);
        assertThat(second.rows).extracting(r -> r.get("invoice_number")).containsExactly("INV-4", "INV-5");
        assertThat(this.documents.requests.get(this.documents.requests.size() - 1).after).isEqualTo(fourth - 1);

        // Run 602 failed: the next run reads its documents again.
        StreamTry third = this.run(since, 603);
        this.task.stream(third);
        assertThat(third.rows).extracting(r -> r.get("invoice_number")).containsExactly("INV-4", "INV-5");
        this.documents.completed.add(603L);

        // Nothing new: an empty read, and the mark stays where it was.
        StreamTry fourthRun = this.run(since, 604);
        assertThat(this.task.stream(fourthRun).getRecordsOut()).isEqualTo(0L);
        assertThat(fourthRun.declared).contains("invoice_number");
        this.documents.completed.add(604L);
        assertThat(this.documents.watermark(77L, "step", TYPE)).isEqualTo(fourth + 1);
    }

    /**
     * Owner 2026-10-21: runs a workflow's requests start side by side read different documents; a failed run's documents
     * come again after a later run's completed watermark passed them.
     */
    @Test
    void runsSideBySideReadDifferentDocumentsAndAFailedRunsComeAgain() throws Exception {
        Fakes.Takes takes = new Fakes.Takes();
        this.task.useTakes(takes);
        for (int i = 1; i <= 2; i++) {
            this.documents.approve(A, TYPE, "INV-" + i, "V-1", 10 * i, 1);
        }
        Map<String, Object> since = config("documentTypeId", TYPE, "since", "last_run");
        StreamTry first = this.run(since, 701);
        this.task.stream(first);
        assertThat(first.rows).extracting(r -> r.get("invoice_number")).containsExactly("INV-1", "INV-2");

        this.documents.approve(A, TYPE, "INV-3", "V-1", 30, 1);
        StreamTry beside = this.run(since, 702);
        this.task.stream(beside);
        assertThat(beside.rows).as("run 702, beside 701, reads only what 701 left").extracting(r -> r.get("invoice_number"))
            .containsExactly("INV-3");

        // 702 completes (the watermark passes INV-1 and INV-2); 701 fails: its documents come again.
        this.documents.completed.add(702L);
        takes.completed.add(702L);
        takes.ended.add(701L);
        StreamTry next = this.run(since, 703);
        this.task.stream(next);
        assertThat(next.rows).extracting(r -> r.get("invoice_number")).containsExactly("INV-1", "INV-2");
    }

    @Test
    void aTablesRowsBecomeRowsWithTheDocumentBesideThem() throws Exception {
        this.documents.approve(A, TYPE, "INV-1", "V-1", 30, 2);
        this.documents.approve(A, TYPE, "INV-2", "V-2", 0, 0);
        StreamTry t = this.run(config("documentTypeId", TYPE, "tableRows", "line_items",
            "columns", Arrays.asList("invoice_number", "sku", "quantity", "amount", "extraction_id")), 701);

        this.task.stream(t);

        assertThat(t.declared).containsExactly("invoice_number", "sku", "quantity", "amount", "extraction_id");
        assertThat(t.rows).extracting(r -> r.get("invoice_number") + " " + r.get("sku") + " " + r.get("quantity"))
            .containsExactly("INV-1 SKU-0 1", "INV-1 SKU-1 2", "INV-2 null null");
    }

    @Test
    void aColumnOrTableTheTypeDoesNotHaveIsSaidByName() {
        this.documents.approve(A, TYPE, "INV-1", "V-1", 30, 1);
        assertThatThrownBy(() -> this.task.stream(this.run(config("documentTypeId", TYPE, "columns", Arrays.asList("invoice_number", "po_nmber")),
            801))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("po_nmber").hasMessageContaining("vendor_id");
        assertThatThrownBy(() -> this.task.stream(this.run(config("documentTypeId", TYPE, "tableRows", "lines"), 802)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("line_items");
        this.documents.refusal = "No document type 1007 in this workspace.";
        assertThatThrownBy(() -> this.task.stream(this.run(config("documentTypeId", TYPE), 803)))
            .hasMessageContaining("No document type 1007");
    }

    @Test
    void withNoTypeItReadsTheDocumentsWhoseApprovalStartedTheRun() throws Exception {
        long one = this.documents.approve(A, TYPE, "INV-1", "V-1", 10, 1);
        this.documents.approve(A, TYPE, "INV-2", "V-1", 20, 1);
        long three = this.documents.approve(A, TYPE, "INV-3", "V-1", 30, 1);
        StreamTry t = this.run(config(), 901);
        t.documents = Arrays.asList(new RunDocuments.Document(this.documents.extractionOf(one), TYPE, 1),
            new RunDocuments.Document(this.documents.extractionOf(three), TYPE, 1));

        this.task.stream(t);

        assertThat(t.rows).extracting(r -> r.get("invoice_number")).containsExactly("INV-1", "INV-3");
        assertThat(this.documents.requests.get(0).extractionIds).hasSize(2);

        StreamTry none = this.run(config(), 902);
        assertThatThrownBy(() -> this.task.stream(none)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not started by an approved document");
    }

    /** The in-memory path (StreamingStepTask.run) is handed the documents that started the run, as a streamed try is. */
    @Test
    void inMemoryWithNoTypeItReadsTheDocumentsWhoseApprovalStartedTheRun() throws Exception {
        long one = this.documents.approve(TaskContext.TENANT, TYPE, "INV-1", "V-1", 10, 1);
        this.documents.approve(TaskContext.TENANT, TYPE, "INV-2", "V-1", 20, 1);
        TaskContext context = new TaskContext(config(), Dataset.EMPTY);
        context.documents = Collections.singletonList(new RunDocuments.Document(this.documents.extractionOf(one), TYPE, 1));

        StepResult result = this.task.run(context);

        assertThat(result.getOutput().getRows()).extracting(r -> r.get("invoice_number")).containsExactly("INV-1");
    }

    @Test
    void aLargeDatasetStreamsAPageAtATime() throws Exception {
        int count = 20_000;
        for (int i = 0; i < count; i++) {
            this.documents.approve(A, TYPE, "INV-" + i, "V-" + (i % 7), i, 0);
        }
        StreamTry t = this.run(config("documentTypeId", TYPE, "columns", Arrays.asList("invoice_number", "total")), 1001);
        t.keep = false;
        long[] last = {-1};
        t.check = row -> {
            long total = ((Number) row.get("total")).longValue();
            assertThat(total).isEqualTo(last[0] + 1);
            last[0] = total;
        };

        assertThat(this.task.stream(t).getRecordsOut()).isEqualTo((long) count);

        assertThat(this.documents.requests).hasSize(count / ReadDocumentsStepTask.PAGE + 1);
        assertThat(this.documents.requests).allSatisfy(r -> {
            assertThat(r.limit).isEqualTo(ReadDocumentsStepTask.PAGE);
            // file_name is not among the columns: Media is not asked for names.
            assertThat(r.names).isFalse();
        });
        assertThat(t.lines).anySatisfy(line -> assertThat(line).contains(count + " document(s)").contains("41 page(s)"));
    }

    @Test
    void reviewAndRejectedDocumentsAddTheirDecisionColumns() throws Exception {
        this.documents.approve(A, TYPE, "INV-1", "V-1", 10, 0);
        StreamTry t = this.run(config("documentTypeId", TYPE, "statuses", Arrays.asList("Approved", "Review", "Rejected")), 1101);
        this.task.stream(t);
        assertThat(t.declared).contains("reject_reason", "date_decided");
        assertThat(this.documents.requests).extracting(r -> r.status).containsExactly("Approved", "Review", "Rejected");
    }

    @Test
    void itsSettingsSayWhatTheyCannotMean() {
        assertThat(problems(config("since", "last_run"))).anySatisfy(p -> assertThat(p).contains("needs the document type"));
        assertThat(problems(config("documentTypeId", TYPE, "since", "last_run", "statuses", Arrays.asList("Approved", "Review"))))
            .anySatisfy(p -> assertThat(p).contains("approved documents only"));
        assertThat(problems(config("statuses", Collections.singletonList("Rejected")))).anySatisfy(p -> assertThat(p).contains("choose a type"));
        assertThat(problems(config("documentTypeId", TYPE, "from", "last Tuesday"))).anySatisfy(p -> assertThat(p).startsWith("from"));
        assertThat(problems(config("documentTypeId", TYPE, "from", "2026-10-01", "to", "{{date}}"))).isEmpty();
        assertThat(problems(config("documentTypeId", TYPE, "columns", Arrays.asList("total", "total")))).hasSize(1);
        assertThat(problems(config("documentTypeId", TYPE, "since", "last_run"))).isEmpty();
    }

    private List<String> problems(Map<String, Object> config) {
        List<DefinitionProblem> problems = this.task.check(config);
        return problems.stream().map(p -> p.getPath() + ": " + p.getMessage()).collect(Collectors.toList());
    }

    @Test
    void theStepIsAReadBackedByDocumentIntelligence() {
        assertThat(this.task.code()).isEqualTo("read_documents");
        assertThat(this.task.spec().backingService()).isEqualTo("ai-service");
        assertThat(DocumentDatasets.MAX_PAGE).isGreaterThanOrEqualTo(ReadDocumentsStepTask.PAGE);
    }
}
