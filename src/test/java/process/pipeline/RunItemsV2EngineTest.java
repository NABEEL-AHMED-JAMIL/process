package process.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import process.engine.BulkAction;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.service.NotifyService;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.backing.Fakes;
import process.pipeline.backing.MediaFiles;
import process.pipeline.backing.ReportStudio;
import process.pipeline.items.InMemoryRunItemStore;
import process.pipeline.items.ItemReviews;
import process.pipeline.items.ItemRow;
import process.pipeline.registry.InMemoryTaskOverrideStore;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskRegistry;
import process.pipeline.registry.TaskSpec;
import process.pipeline.tasks.GenerateReportStepTask;
import process.pipeline.tasks.OcrPagesStepTask;
import process.pipeline.tasks.SampleStepTask;
import process.pipeline.tasks.SplitPagesStepTask;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;
import static process.pipeline.Definitions.sample;
import static process.pipeline.Definitions.step;

/**
 * MIG-421, Run Items v2, through the step engine and RunRetries: a Generate report step keeps each item's report as soon as it
 * is made -- a retry makes only the reports the failed items need and reuses the rest (not made, not billed, version
 * untouched); each item keeps its own values and the pipeline version it ran; and the OCR step reads only the pages with no
 * text layer, marks the ones it is not sure of "Check by hand" (the page, and its document) and sends them to Review.
 */
class RunItemsV2EngineTest {

    private static final long TENANT = 1006L;
    private static final long JOB_ID = 1094L;
    private static final long RUN_ID = 9902L;
    private static final long USER = 1015L;
    private static final String DESIGN = "01JABCDEFGHJKMNPQRSTVWXYZ0";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final InMemoryDatasetStore datasets = new InMemoryDatasetStore();
    private final InMemoryRunItemStore items = new InMemoryRunItemStore();
    private final RunRetriesTest.MemoryAttempts attempts = new RunRetriesTest.MemoryAttempts();
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final TransactionServiceImpl transactions = mock(TransactionServiceImpl.class);
    private final BulkAction bulk = mock(BulkAction.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RunItemsEngineTest.Pdfs buckets = new RunItemsEngineTest.Pdfs();
    private final ExecutorService tryThreads = Executors.newCachedThreadPool();
    private final Map<Long, PipelineDefinitionStore.Stored> versions = new LinkedHashMap<>();
    private final Studio studio = new Studio();
    private final Fakes.Media media = new Fakes.Media();
    private final Reviews reviews = new Reviews();

    private StepEngine engine;
    private RunRetries retries;
    private JobQueue run;
    private SourceJob job;
    private long nextVersionId = 4100;

    /** Report Studio as the step sees it: every report it is asked to make (what media bills), and the rows it refuses. */
    static final class Studio implements ReportStudio {
        final List<String> made = new CopyOnWriteArrayList<>();
        final List<String> downloaded = new CopyOnWriteArrayList<>();
        final Set<String> refuse = ConcurrentHashMap.newKeySet();
        final Map<String, ObjectNode> jobs = new ConcurrentHashMap<>();
        int next = 18;

        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }

        @Override
        public synchronized JsonNode generate(long tenantId, Long userId, String userName, String key, Path lines) throws IOException {
            List<String> sent = Files.readAllLines(lines, StandardCharsets.UTF_8);
            String record = sent.get(1);
            String label = String.format("RPT-%04d", this.next++);
            this.made.add(key + " " + JSON.readTree(record).path("key").asText());
            ObjectNode j = JSON.createObjectNode().put("jobId", "J" + this.next).put("reportId", "R" + label).put("reportLabel", label)
                .put("templateVersion", 3);
            boolean refused = this.refuse.stream().anyMatch(record::contains);
            if (refused) {
                j.put("status", "FAILED").put("errorCode", "RENDER_FAILED").put("error", "The renderer stopped.");
            } else {
                j.put("status", "SUCCEEDED").put("resultVersion", 1);
                j.putArray("artifacts").addObject().put("format", "PDF").put("name", label + ".pdf").put("pages", 2);
                j.putObject("checks").put("errors", 0).put("warnings", 0).putArray("firstCodes");
            }
            this.jobs.put(j.path("jobId").asText(), j);
            return j;
        }

        @Override
        public JsonNode job(long tenantId, String jobId) {
            return this.jobs.get(jobId);
        }

        @Override
        public void file(long tenantId, String reportId, int version, String format, OutputStream out) throws IOException {
            this.downloaded.add(reportId + " v" + version);
            out.write(("%PDF-1.7 " + reportId).getBytes(StandardCharsets.ISO_8859_1));
        }

        @Override
        public JsonNode check(long tenantId, Collection<String> ids, String version, Collection<String> columns) {
            ArrayNode out = JSON.createArrayNode();
            out.addObject().put("templateId", DESIGN).put("found", true).put("name", "Call insight").put("currentPublishedVersion", 3)
                .putArray("missingFields");
            return out;
        }
    }

    /** The Task inbox as the OCR step reaches it. */
    static final class Reviews implements ItemReviews {
        final List<ReviewRequest> sent = new CopyOnWriteArrayList<>();

        @Override
        public Optional<String> unavailable() {
            return Optional.empty();
        }

        @Override
        public Started send(ReviewRequest request) {
            this.sent.add(request);
            return new Started(77, "Running");
        }
    }

    @BeforeEach
    void setUp() {
        OcrPagesStepTask ocr = new OcrPagesStepTask(this.media);
        ocr.useReviews(this.reviews);
        StepTasks tasks = new StepTasks(new ArrayList<>(Arrays.asList(new SampleStepTask(), new PerItem(), new GenerateReportStepTask(this.studio),
            new SplitPagesStepTask(this.buckets), ocr)));
        DefinitionValidator validator = new DefinitionValidator(new TaskRegistry(tasks, new InMemoryTaskOverrideStore()));
        this.engine = new StepEngine(this.definitions, this.steps, tasks, validator, this.datasets, new Worker(), this.transactions,
            TransactionOperations.withoutTransaction(), Executors.newSingleThreadExecutor(), this.tryThreads, duration -> { });
        this.engine.useAttempts(this.attempts);
        this.engine.useItems(this.items);
        this.retries = new RunRetries(this.steps, this.attempts, this.definitions, validator, tasks, this.bulk, this.jdbc,
            TransactionOperations.withoutTransaction());
        this.run = new JobQueue();
        this.run.setJobQueueId(RUN_ID);
        this.run.setJobId(JOB_ID);
        this.run.setTenantId(TENANT);
        this.run.setAttempt(1);
        this.run.setJobStatus(JobStatus.Queue);
        this.run.setInputBucket("riverside-health-s3");
        SourceTask task = new SourceTask();
        task.setPipelineId("INTAKE");
        task.setTaskPayload("<pipeline><x>1</x></pipeline>");
        this.job = new SourceJob();
        this.job.setJobId(JOB_ID);
        this.job.setTenantId(TENANT);
        this.job.setJobStatus(Status.Active);
        this.job.setTaskDetail(task);
        when(this.transactions.findJobQueueByJobQueueId(RUN_ID)).thenAnswer(call -> Optional.of(this.run));
        doAnswer(call -> null).when(this.transactions).saveJobAuditLogs(anyLong(), anyString());
        when(this.definitions.byId(anyLong())).thenAnswer(call -> Optional.ofNullable(this.versions.get((Long) call.getArgument(0))));
        when(this.definitions.latestFor(TENANT, "INTAKE")).thenAnswer(call -> this.versions.values().stream().reduce((a, b) -> b));
        when(this.jdbc.update(anyString(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            Object[] args = Arrays.copyOfRange(call.getArguments(), 1, 6);
            if (!this.run.getJobStatus().name().equals(args[3]) || this.run.getAttempt() != ((Number) args[4]).intValue()) {
                return 0;
            }
            this.run.setAttempt(((Number) args[0]).intValue());
            this.run.setJobStatus(JobStatus.Queue);
            this.run.setJobSend(false);
            this.run.setJobStatusMessage((String) args[1]);
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        this.tryThreads.shutdownNow();
    }

    final class Worker implements NotifyService {
        @Override public synchronized ResponseDto changeState(SourceJobQueueDto dto) {
            run.setJobStatus(dto.getJobStatus());
            run.setJobStatusMessage(dto.getJobStatusMessage());
            return new ResponseDto("SUCCESS", "ok");
        }

        @Override public ResponseDto addLogs(SourceJobQueueDto dto) { throw new UnsupportedOperationException(); }

        @Override public ResponseDto addLogsBatch(Long jobId, Long jobQueueId, List<String> messages) { throw new UnsupportedOperationException(); }

        @Override public ResponseDto changeState(SourceJobQueueDto dto, String key) { throw new UnsupportedOperationException(); }

        @Override public ResponseDto addLogs(SourceJobQueueDto dto, String key) { throw new UnsupportedOperationException(); }

        @Override public ResponseDto addLogsBatch(Long jobId, Long jobQueueId, List<String> messages, String key) { throw new UnsupportedOperationException(); }

        @Override public void noteRefusedCallback(Long jobQueueId, JobStatus reportedStatus) { throw new UnsupportedOperationException(); }

        @Override public Optional<ResponseDto> replay(Long jobQueueId, JobStatus s, String request, String key) { throw new UnsupportedOperationException(); }
    }

    /** A per-item step: an answer a row, with a risk the report and the across-runs search read. */
    static final class PerItem implements StepTask {
        private final TaskSpec spec;

        PerItem() {
            Map<String, Object> free = new LinkedHashMap<>();
            free.put("type", "object");
            this.spec = TaskSpec.builder("per_item", "Per item", TaskKind.PROCESS).description("one call a row").config(free)
                .items(ItemRole.EACH).build();
        }

        @Override public String code() { return "per_item"; }

        @Override public String description() { return "one call a row"; }

        @Override public TaskSpec spec() { return this.spec; }

        @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }

        @Override public StepResult run(StepContext context) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : context.input().getRows()) {
                Map<String, Object> answered = new LinkedHashMap<>(row);
                answered.put("status.risk", String.valueOf(row.get("key")).contains("call-b") ? "High" : "Moderate");
                answered.put("summary", "Synthetic summary of " + row.get("key"));
                answered.put("insight_json", "{\"actions\": [1, 2]}");
                out.add(answered);
            }
            return StepResult.of(new Dataset(new ArrayList<>(out.get(0).keySet()), out));
        }
    }

    private long saves(PipelineDefinition definition) {
        PipelineDefinitionStore.Stored stored = new PipelineDefinitionStore.Stored();
        stored.id = this.nextVersionId++;
        stored.tenantId = TENANT;
        stored.version = this.versions.size() + 1;
        stored.json = DefinitionCodec.toJson(definition);
        this.versions.put(stored.id, stored);
        return stored.id;
    }

    private void runs() {
        Optional<StepEngine.StepPlan> plan = this.engine.planFor(this.job, this.run);
        assertThat(plan).isPresent();
        this.run.setJobSend(true);
        this.engine.run(plan.get());
        this.job.setJobRunningStatus(this.run.getJobStatus());
    }

    private ItemRow item(String key) {
        return this.items.currentOf(RUN_ID, key);
    }

    private static PipelineDefinition calls() {
        return Definitions.of(sample("read", row("key", "voice-intake/call-a.m4a"), row("key", "voice-intake/call-c.m4a"),
                row("key", "voice-intake/call-d.m4a"), row("key", "voice-intake/call-b.m4a")),
            step("ask", "per_item"),
            step("report", "generate_report", config("design", "TEMPLATE", "templateId", DESIGN, "perRow", true, "reportName",
                "Call insight · {{key}}")));
    }

    /**
     * The design's KeepReports screen (v1's leftover, live run 1857: one extra convert.documents): attempt 1 makes a, c and d's
     * reports, then stops at b's. Each made report is kept and marked done for its item at once; retrying b makes only b's
     * report -- the step reuses the other three (their PDFs read again from their own versions), so one report is made and
     * billed, and the others keep their version.
     */
    @Test
    void aRetryMakesOnlyTheFailedItemsReportAndReusesTheOthersMadeBeforeTheFailure() {
        this.studio.refuse.add("call-b");
        this.saves(calls());
        this.runs();

        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Failed);
        assertThat(this.studio.made).hasSize(4);
        for (String done : new String[] {"call-a", "call-c", "call-d"}) {
            ItemRow made = this.item("voice-intake/" + done + ".m4a");
            assertThat(made.status).as(done + ": its report was made, so it is done, though the step stopped at b").isEqualTo("done");
            assertThat(made.step(2).status).isEqualTo("done");
            assertThat(made.step(2).note).contains("1 file made").contains("call-b.m4a");
        }
        ItemRow b = this.item("voice-intake/call-b.m4a");
        assertThat(b.status).isEqualTo("failed");
        assertThat(b.failedStepKey).isEqualTo("report");
        assertThat(this.items.reports).extracting(r -> r.itemKey + " " + r.reportLabel + " v" + r.reportVersion + " a" + r.madeAttempt)
            .containsExactly("voice-intake/call-a.m4a RPT-0018 v1 a1", "voice-intake/call-c.m4a RPT-0019 v1 a1",
                "voice-intake/call-d.m4a RPT-0020 v1 a1");

        this.studio.refuse.clear();
        int downloadedBefore = this.studio.downloaded.size();
        RunRetries.Outcome outcome = this.retries.retryItems(this.run, this.job, 2, Collections.singletonList("voice-intake/call-b.m4a"),
            "failed_step", RunRetries.Actor.person(USER, true));
        assertThat(outcome.queued()).as(String.valueOf(outcome.refusal)).isTrue();
        this.runs();

        assertThat(this.run.getJobStatus()).as(this.run.getJobStatusMessage()).isEqualTo(JobStatus.Completed);
        assertThat(this.studio.made.subList(4, this.studio.made.size())).as("only b's report is made (and billed) again")
            .hasSize(1).allMatch(l -> l.endsWith(" voice-intake/call-b.m4a") && l.contains("#a2#"));
        assertThat(this.studio.downloaded.subList(downloadedBefore, this.studio.downloaded.size()))
            .as("the others are reused: their PDFs read from their own versions; b's is the one made")
            .containsExactlyInAnyOrder("RRPT-0018 v1", "RRPT-0019 v1", "RRPT-0020 v1", "RRPT-0022 v1");
        ItemRow retried = this.item("voice-intake/call-b.m4a");
        assertThat(retried.attempt).isEqualTo(2);
        assertThat(retried.status).isEqualTo("done");
        assertThat(this.item("voice-intake/call-a.m4a").attempt).as("a keeps its first attempt's report and version").isEqualTo(1);
        assertThat(this.items.reports).extracting(r -> r.itemKey + " a" + r.madeAttempt).containsExactly("voice-intake/call-a.m4a a1",
            "voice-intake/call-c.m4a a1", "voice-intake/call-d.m4a a1", "voice-intake/call-b.m4a a2");
        assertThat(this.steps.logText(RUN_ID, 2, "report")).anyMatch(l -> l.contains("1 report(s) made and 3 reused"));
    }

    /** "Retry this item" on an item whose data did not change: its report is reused, not made again; changed data makes a new one. */
    @Test
    void aRetriedItemWithTheSameDataReusesItsReportAndChangedDataMakesANewOne() {
        this.studio.refuse.add("call-b");
        this.saves(calls());
        this.runs();
        this.studio.refuse.clear();
        int before = this.studio.made.size();
        assertThat(this.retries.retryItems(this.run, this.job, 1, Arrays.asList("voice-intake/call-b.m4a"), "item",
            RunRetries.Actor.person(USER, true)).queued()).isTrue();
        this.runs();
        assertThat(this.studio.made.subList(before, this.studio.made.size())).hasSize(1);

        // b again, its data unchanged: its attempt-2 report is reused.
        this.run.setJobStatus(JobStatus.Failed);
        ItemRow b = this.item("voice-intake/call-b.m4a");
        b.status = "failed";
        b.failedStepIndex = 2;
        b.failedStepKey = "report";
        b.reason = "Marked failed by the test.";
        this.items.save(Collections.singletonList(b));
        int again = this.studio.made.size();
        assertThat(this.retries.retryItems(this.run, this.job, 2, Arrays.asList("voice-intake/call-b.m4a"), "failed_step",
            RunRetries.Actor.person(USER, true)).queued()).isTrue();
        this.runs();
        assertThat(this.studio.made).as("same data and design: reused").hasSize(again);
        ItemRow reused = this.item("voice-intake/call-b.m4a");
        assertThat(reused.step(2).reusedFrom).isEqualTo(2);
        assertThat(reused.step(2).note).contains("reused").contains("RPT-");
    }

    /** Each item keeps its own values (scalars, not JSON text) and the pipeline version it ran, for the across-runs search. */
    @Test
    void eachItemKeepsItsValuesAndThePipelineVersionItRan() {
        this.saves(calls());
        this.runs();

        ItemRow b = this.item("voice-intake/call-b.m4a");
        assertThat(b.pipelineVersion).isEqualTo(1);
        assertThat(b.fields).containsEntry("status.risk", "High").containsEntry("key", "voice-intake/call-b.m4a")
            .doesNotContainKey("insight_json");
        assertThat(b.fieldsDs).isNotNull();
        assertThat(this.item("voice-intake/call-a.m4a").fields).containsEntry("status.risk", "Moderate");
    }

    /**
     * The OcrStep screen: a 3-page referral, page 1 with a text layer (kept, not read or billed), pages 2 and 3 scanned (read
     * by OCR, billed per page). Page 2 is read at 64 %, under the 80 % bar: marked Check by hand -- the page and its document --
     * and sent to Review as one Task inbox task naming it and linking to the run's pages to check.
     */
    @Test
    void theOcrStepReadsOnlyPagesWithNoTextLayerAndSendsTheUnsureOnesToReview() throws Exception {
        this.buckets.objects.put("riverside-health-s3/referrals/referral-0412.pdf",
            RunItemsEngineTest.pdf("REFERRAL FOR SPECIALIST OPINION, Riverside Family Practice", null, null));
        this.media.sureness.put("referral-0412-page-02.png", 64.0);
        this.media.sureness.put("referral-0412-page-03.png", 93.0);
        this.media.rotation.put("referral-0412-page-03.png", 180);
        this.saves(Definitions.of(sample("read", row("key", "referrals/referral-0412.pdf", "bucket", "riverside-health-s3")),
            step("pages", "split_pages", config("minChars", 0)),
            step("ocr", "ocr_pages", config("fromStep", "pages", "threshold", 80, "reviewers", config("kind", "team", "value", "1063")))));

        this.runs();

        assertThat(this.run.getJobStatus()).as(this.run.getJobStatusMessage()).isEqualTo(JobStatus.Completed);
        assertThat(this.media.calls).as("the text-layer page is not read; the two scans are, each billed once")
            .hasSize(2).allMatch(c -> c.startsWith("ocr 1006 pipeline#9902#ocr#a1#ri"))
            .anyMatch(c -> c.endsWith("referral-0412-page-02.png")).anyMatch(c -> c.endsWith("referral-0412-page-03.png"));
        ItemRow page2 = this.item("referrals/referral-0412.pdf#2");
        assertThat(page2.status).isEqualTo("done");
        assertThat(page2.flag).isEqualTo(ItemRow.CHECK);
        assertThat(page2.step(2).note).isEqualTo("Check by hand: 64 % sure (below 80 %).");
        assertThat(this.item("referrals/referral-0412.pdf#3").flag).isNull();
        assertThat(this.item("referrals/referral-0412.pdf#1").flag).isNull();
        assertThat(this.item("referrals/referral-0412.pdf").flag).as("a document with a page to check is one to check").isEqualTo(ItemRow.CHECK);
        assertThat(this.reviews.sent).hasSize(1);
        ItemReviews.ReviewRequest sent = this.reviews.sent.get(0);
        assertThat(sent.tenantId).isEqualTo(TENANT);
        assertThat(sent.reviewersKind).isEqualTo("team");
        assertThat(sent.reviewersValue).isEqualTo("1063");
        assertThat(sent.title).isEqualTo("Check 1 page by hand: pipeline, run #9902");
        assertThat(sent.eventId).isEqualTo("ocr-check-9902-ocr-a1");
        assertThat(sent.subject.get("items")).isEqualTo(Collections.singletonList("referral-0412.pdf › Page 2 (64 % sure)"));
        assertThat(sent.subject.get("link")).isEqualTo("/pipelines/schedules/1094/runs/9902?tab=items&status=check");
        assertThat(this.item("referrals/referral-0412.pdf#1").fields).as("the page's values: its own text layer, kept")
            .containsEntry("ocr_source", "text-layer").containsEntry("ocr_status", "ok");
        assertThat(this.item("referrals/referral-0412.pdf#2").fields).containsEntry("ocr_status", "check").containsEntry("ocr_sureness", 64.0);
        // ZT-I25: the page sub-item says how far its page was turned to be read upright.
        assertThat(this.item("referrals/referral-0412.pdf#3").fields).as("a page scanned upside down").containsEntry("ocr_rotation", 180)
            .containsEntry("ocr_note", "Read: 3 words, 93 % sure. Turned 180° to be read upright.");
        assertThat(this.item("referrals/referral-0412.pdf#2").fields).containsEntry("ocr_rotation", 0);
        assertThat(this.item("referrals/referral-0412.pdf#1").fields).as("a text layer is not turned").containsEntry("ocr_rotation", 0);
    }

    /** A page the reader cannot read is that page's failure (kept, text empty); "Retry page" reads it alone, billed once more. */
    @Test
    void aPageTheReaderRefusesFailsAloneAndIsRetriedAlone() throws Exception {
        this.buckets.objects.put("riverside-health-s3/referrals/referral-0412.pdf", RunItemsEngineTest.pdf(null, null));
        this.media.refusals.put("referral-0412-page-01.png", new MediaFiles.Refused(422, "The page could not be read.", 0));
        this.saves(Definitions.of(sample("read", row("key", "referrals/referral-0412.pdf", "bucket", "riverside-health-s3")),
            step("pages", "split_pages", config("minChars", 0)), step("ocr", "ocr_pages", config("fromStep", "pages"))));
        this.runs();

        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Completed);
        ItemRow page1 = this.item("referrals/referral-0412.pdf#1");
        assertThat(page1.status).isEqualTo("failed");
        assertThat(page1.reason).isEqualTo("The page could not be read.");
        assertThat(this.item("referrals/referral-0412.pdf#2").status).isEqualTo("done");
        assertThat(this.reviews.sent).as("nothing to check by hand").isEmpty();

        this.media.refusals.clear();
        int before = this.media.calls.size();
        assertThat(this.retries.retryItems(this.run, this.job, 2, Collections.singletonList("referrals/referral-0412.pdf#1"), "failed_step",
            RunRetries.Actor.person(USER, true)).queued()).isTrue();
        this.runs();
        assertThat(this.media.calls.subList(before, this.media.calls.size())).hasSize(1).allMatch(c -> c.endsWith("referral-0412-page-01.png"));
        assertThat(this.item("referrals/referral-0412.pdf#1").status).isEqualTo("done");
        assertThat(this.item("referrals/referral-0412.pdf#2").attempt).isEqualTo(1);
    }
}
