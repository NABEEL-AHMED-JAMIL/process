package process.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.barco.platform.meter.Meter;
import org.barco.platform.meter.MeterReporter;
import org.barco.platform.meter.UsageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.AiPromptDto;
import process.model.enums.Status;
import process.model.pojo.AiModelConnection;
import process.model.pojo.AiPrompt;
import process.model.pojo.AiPromptRun;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.repository.AiModelConnectionRepository;
import process.model.repository.AiPromptRepository;
import process.model.repository.AiPromptRunRepository;
import process.model.repository.PipelineRepository;
import process.util.EncryptionUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-145: a refusal and a failure are different things, and the difference is money.
 *
 * A refusal is the runner (or the step service) declining before any provider call: an empty
 * required variable, the daily budget, no connection, a prompt no longer active. Nothing was spent,
 * so the row carries no tokens and the meter hears nothing. A failure is a call that was made and
 * went wrong afterwards -- the model answered, but never with the JSON the prompt asked for -- and
 * those tokens were paid for, so the row carries them and they are metered, because metering runs
 * after both catch blocks, not inside the success branch.
 *
 * Pinned before the AI service is extracted (recipe step 1; 05-service-map §AI; analysis/04-ai):
 * the new service must answer every one of these the same way, or historical run rows and new ones
 * stop meaning the same thing.
 */
class PromptRunnerRefusalVersusFailureTest {

    private static final long TENANT = 2905L;

    private final AiProviderGateway gateway = mock(AiProviderGateway.class);
    private final AiPromptRunRepository runs = mock(AiPromptRunRepository.class);
    private final MeterReporter meter = mock(MeterReporter.class);
    private final AtomicLong nextRunId = new AtomicLong(9000L);
    private final List<AiPromptRun> saved = new ArrayList<>();
    private PromptRunner runner;
    private ListAppender<ILoggingEvent> runnerLog;

    @BeforeEach
    void setUp() {
        when(this.runs.save(any(AiPromptRun.class))).thenAnswer(inv -> {
            AiPromptRun row = inv.getArgument(0);
            ReflectionTestUtils.setField(row, "runId", this.nextRunId.incrementAndGet());
            this.saved.add(row);
            return row;
        });
        this.runner = new PromptRunner(this.gateway, this.runs);
        ReflectionTestUtils.setField(this.runner, "meter", this.meter);
        this.runnerLog = new ListAppender<>();
        this.runnerLog.start();
        ((Logger) LoggerFactory.getLogger(PromptRunner.class)).addAppender(this.runnerLog);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(PromptRunner.class)).detachAppender(this.runnerLog);
    }

    private static AiModelConnection connection(Long budget) {
        AiModelConnection c = new AiModelConnection();
        c.setConnectionId(7L); c.setName("OpenAI · production"); c.setProvider("OpenAI"); c.setDefaultModel("gpt-4.1-mini");
        c.setMaxConcurrency(4); c.setDailyTokenBudget(budget);
        return c;
    }

    private static PromptRunner.Job job(String outputMode) {
        PromptRunner.Job job = new PromptRunner.Job();
        job.tenantId = TENANT; job.promptId = 1000L; job.kind = "run"; job.jobQueueId = 55L; job.stepTag = "summary";
        job.connection = connection(null); job.model = "gpt-4.1-mini"; job.template = "Summarise {{text}}";
        job.values.put("text", "the claim"); job.outputMode = outputMode;
        return job;
    }

    private static AiPromptDto.Variable required(String name) {
        AiPromptDto.Variable v = new AiPromptDto.Variable();
        v.name = name; v.required = true; v.type = "text";
        return v;
    }

    private List<UsageEvent> metered() {
        ArgumentCaptor<UsageEvent> captor = ArgumentCaptor.forClass(UsageEvent.class);
        verify(this.meter, atLeast(0)).report(captor.capture());
        return captor.getAllValues();
    }

    // ---- run() never throws, and always leaves exactly one row ----------------------------------

    /**
     * Every caller -- Try it, a pipeline step at dispatch, the worker's callback -- reads the row it
     * gets back and never wraps the call in a try. One exception escaping would fail a dispatch pass
     * with no row saying why. Each branch below lands in a different arm of the try: the success
     * path, the IllegalArgument/IllegalState "refusal" catch, and the catch-all.
     */
    @Test
    void runNeverThrowsAndSavesExactlyOneRowOnEveryPath() throws Exception {
        PromptRunner.Job ok = job("text");
        PromptRunner.Job emptyRequired = job("text");
        emptyRequired.variables.add(required("text")); emptyRequired.values.clear();
        PromptRunner.Job noConnection = job("text");
        noConnection.connection = null;
        PromptRunner.Job providerRefused = job("text");
        PromptRunner.Job providerExploded = job("text");
        PromptRunner.Job providerAnsweredNothing = job("text");

        when(this.gateway.chat(any()))
            .thenReturn(new AiProviderGateway.ChatAnswer("fine", 10, 2))
            .thenThrow(new AiProviderGateway.ProviderException(401, "HTTP 401: bad key"))
            .thenThrow(new RuntimeException("socket closed"))
            .thenReturn(null);

        List<AiPromptRun> rows = new ArrayList<>();
        for (PromptRunner.Job j : new PromptRunner.Job[] {ok, emptyRequired, noConnection, providerRefused, providerExploded, providerAnsweredNothing}) {
            assertThatCode(() -> rows.add(this.runner.run(j))).doesNotThrowAnyException();
        }

        assertThat(rows).extracting(AiPromptRun::getStatus).containsExactly("ok", "failed", "failed", "failed", "failed", "failed");
        assertThat(rows).allSatisfy(r -> assertThat(r.getRunId()).as("the row came back saved").isNotNull());
        verify(this.runs, times(6)).save(any(AiPromptRun.class));
        assertThat(rows.get(1).getError()).contains("text").contains("required");
        assertThat(rows.get(2).getError()).startsWith("No model connection");
        assertThat(rows.get(3).getError()).isEqualTo("HTTP 401: bad key");
        assertThat(rows.get(4).getError()).isEqualTo("socket closed");
        // A null answer is an NPE inside the try, caught by the catch-all like any other exception.
        assertThat(rows.get(5).getError()).isNotBlank();
    }

    // ---- a refusal: no call, no tokens, nothing metered -----------------------------------------

    /**
     * The runner's own refusals: the budget, an empty required variable, a missing connection.
     * The row is "failed", its tokens are NULL (not zero), no call is made and the meter hears
     * nothing. Its latency is the wall-clock time of the refusal, which is the time it took to
     * render and ask the repository -- a few milliseconds at most, not a forced zero.
     */
    @Test
    void theRunnersRefusalIsFailedWithNullTokensAndIsNeverMetered() throws Exception {
        PromptRunner.Job overBudget = job("text");
        overBudget.connection = connection(1_000L);
        when(this.runs.tokensSince(anyLong(), any())).thenReturn(1_000L);
        PromptRunner.Job emptyRequired = job("text");
        emptyRequired.variables.add(required("text")); emptyRequired.values.clear();
        PromptRunner.Job noConnection = job("text");
        noConnection.connection = null;

        long before = System.currentTimeMillis();
        List<AiPromptRun> rows = new ArrayList<>();
        rows.add(this.runner.run(overBudget));
        rows.add(this.runner.run(emptyRequired));
        rows.add(this.runner.run(noConnection));
        long elapsed = System.currentTimeMillis() - before;

        verify(this.gateway, never()).chat(any());
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo("failed");
            assertThat(r.getTokensIn()).isNull();
            assertThat(r.getTokensOut()).isNull();
            assertThat(r.getOutput()).isNull();
            assertThat(r.getLatencyMs()).isNotNull().isBetween(0, (int) elapsed);
        });
        assertThat(rows.get(0).getError()).startsWith("Daily token budget reached").endsWith("No call was made.");
        assertThat(this.metered()).isEmpty();
    }

    /**
     * The step service's refusals -- a prompt no longer active, no connection for it, a step the
     * pipeline does not hand to the worker -- never reach the runner at all. Their row is written
     * by AiStepService.refused with latencyMs set to exactly 0 and no tokens, and since the runner's
     * metering is the only metering there is, nothing is reported.
     */
    @Test
    void theStepServicesRefusalHasLatencyZeroNullTokensAndIsNeverMetered() throws Exception {
        PipelineRepository pipelines = mock(PipelineRepository.class);
        AiPromptRepository prompts = mock(AiPromptRepository.class);
        AiModelConnectionRepository connections = mock(AiModelConnectionRepository.class);
        Pipeline pipeline = new Pipeline();
        pipeline.setPipelineId("F1"); pipeline.setTenantId(TENANT); pipeline.setStatus(Status.Active);
        PipelineField step = new PipelineField();
        step.setTagKey("summary"); step.setFieldType("ai"); step.setPosition(0); step.setPromptId(1000L); step.setOnError("fail");
        pipeline.getFields().add(step);
        AiPrompt inactive = new AiPrompt();
        inactive.setPromptId(1000L); inactive.setTenantId(TENANT); inactive.setStatus(Status.Inactive); inactive.setVersion(1);
        when(pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", TENANT, Status.Delete)).thenReturn(Collections.singletonList(pipeline));
        when(prompts.findById(1000L)).thenReturn(Optional.of(inactive));
        when(this.runs.findByJobQueueIdAndStepTag(55L, "summary")).thenReturn(Optional.empty());
        AiStepService steps = new AiStepService(pipelines, prompts, connections, this.runs, mock(EncryptionUtil.class), this.runner);

        AiStepService.Outcome outcome = steps.apply(TENANT, "F1", 55L, "<pipeline><text>x</text></pipeline>");

        assertThat(outcome.failed()).isTrue();
        assertThat(this.saved).hasSize(1);
        AiPromptRun row = this.saved.get(0);
        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getLatencyMs()).isEqualTo(0);
        assertThat(row.getTokensIn()).isNull();
        assertThat(row.getTokensOut()).isNull();
        verify(this.gateway, never()).chat(any());
        assertThat(this.metered()).isEmpty();
    }

    // ---- a failure after a call: still billed ---------------------------------------------------

    /**
     * The model answered twice and never with the JSON the prompt expects. Both rounds were paid
     * for, so the row carries round one's tokens PLUS the repair round's, and the meter is told
     * both -- metered() runs after the catch blocks, on whatever the saved row carries.
     */
    @Test
    void aFailedRunThatCalledIsStillBilledForBothRoundsAdded() throws Exception {
        PromptRunner.Job j = job("json");
        j.outputSchema = "{\"required\":[\"diagnosis\"]}";
        when(this.gateway.chat(any()))
            .thenReturn(new AiProviderGateway.ChatAnswer("not json at all", 100, 20))
            .thenReturn(new AiProviderGateway.ChatAnswer("{\"other\": 1}", 130, 25));

        AiPromptRun row = this.runner.run(j);

        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getError()).startsWith("The answer is not the JSON the prompt expects: missing key(s): diagnosis");
        assertThat(row.getTokensIn()).isEqualTo(230);
        assertThat(row.getTokensOut()).isEqualTo(45);
        List<UsageEvent> events = this.metered();
        assertThat(events).extracting(e -> e.meter).containsExactly(Meter.AI_TOKENS_IN.key(), Meter.AI_TOKENS_OUT.key());
        assertThat(events).extracting(e -> e.quantity).containsExactly(230.0, 45.0);
        assertThat(events).extracting(e -> e.dedupeKey).containsExactly("ai-run#" + row.getRunId() + "#in", "ai-run#" + row.getRunId() + "#out");
        assertThat(events).allSatisfy(e -> assertThat(e.tenantId).isEqualTo(TENANT));
    }

    /** A successful repair is billed the same way: round one's tokens are added to, not replaced. */
    @Test
    void aSuccessfulRepairRoundAddsItsTokensToRoundOnes() throws Exception {
        PromptRunner.Job j = job("json");
        j.outputSchema = "{\"required\":[\"diagnosis\"]}";
        when(this.gateway.chat(any()))
            .thenReturn(new AiProviderGateway.ChatAnswer("{}", 100, 20))
            .thenReturn(new AiProviderGateway.ChatAnswer("{\"diagnosis\": \"ok\"}", 130, 25));

        AiPromptRun row = this.runner.run(j);

        assertThat(row.getStatus()).isEqualTo("ok");
        assertThat(row.getTokensIn()).isEqualTo(230);
        assertThat(row.getTokensOut()).isEqualTo(45);
        assertThat(this.metered()).extracting(e -> e.quantity).containsExactly(230.0, 45.0);
    }

    /**
     * Round one answered, the repair round's call then failed outright. Round one was still paid
     * for and is still billed: its tokens were set on the row before the repair was attempted.
     */
    @Test
    void aRepairRoundThatErrorsStillBillsRoundOne() throws Exception {
        PromptRunner.Job j = job("json");
        j.outputSchema = "{\"required\":[\"diagnosis\"]}";
        when(this.gateway.chat(any()))
            .thenReturn(new AiProviderGateway.ChatAnswer("{}", 100, 20))
            .thenThrow(new AiProviderGateway.ProviderException(401, "HTTP 401: key revoked mid-run"));

        AiPromptRun row = this.runner.run(j);

        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getTokensIn()).isEqualTo(100);
        assertThat(row.getTokensOut()).isEqualTo(20);
        assertThat(this.metered()).extracting(e -> e.quantity).containsExactly(100.0, 20.0);
    }

    /**
     * A call that never produced an answer -- the provider refused it, or it threw -- carries no
     * usage to bill, so the row's tokens stay null and nothing is metered. "Billed on failure"
     * means billed for what the provider reported, never guessed.
     */
    @Test
    void aCallThatNeverAnsweredHasNothingToBill() throws Exception {
        when(this.gateway.chat(any())).thenThrow(new AiProviderGateway.ProviderException(400, "HTTP 400: bad request"));

        AiPromptRun row = this.runner.run(job("text"));

        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getTokensIn()).isNull();
        assertThat(row.getTokensOut()).isNull();
        assertThat(this.metered()).isEmpty();
    }

    // ---- token accounting clamps with max(v, 0) --------------------------------------------------

    /**
     * The gateway says -1 for "the provider reported no usage". The runner clamps with max(v, 0),
     * so the row says 0 -- the difference between "zero tokens" and "unknown" is lost at
     * persistence, knowingly (business rule: historical and new rows must mean the same thing).
     * A zero is then not metered, since only positive counts are reported.
     */
    @Test
    void unknownUsageIsPersistedAsZeroAndNotMetered() throws Exception {
        when(this.gateway.chat(any())).thenReturn(new AiProviderGateway.ChatAnswer("an answer", -1, -1));

        AiPromptRun row = this.runner.run(job("text"));

        assertThat(row.getStatus()).isEqualTo("ok");
        assertThat(row.getTokensIn()).isEqualTo(0);
        assertThat(row.getTokensOut()).isEqualTo(0);
        assertThat(this.metered()).isEmpty();
    }

    /** The clamp applies to each round separately: an unknown repair round adds nothing and subtracts nothing. */
    @Test
    void anUnknownRepairRoundAddsZeroToRoundOne() throws Exception {
        PromptRunner.Job j = job("json");
        j.outputSchema = "{\"required\":[\"diagnosis\"]}";
        when(this.gateway.chat(any()))
            .thenReturn(new AiProviderGateway.ChatAnswer("{}", 100, 20))
            .thenReturn(new AiProviderGateway.ChatAnswer("{\"diagnosis\": 1}", -1, -1));

        AiPromptRun row = this.runner.run(j);

        assertThat(row.getTokensIn()).isEqualTo(100);
        assertThat(row.getTokensOut()).isEqualTo(20);
    }

    // ---- the 200,000-character input truncation is silent ----------------------------------------

    /**
     * MAX_INPUT_CHARS = 200_000. A longer rendered input is cut to exactly that and sent; the run
     * is "ok", its error is empty, and the runner logs nothing about it. The JSON instruction is
     * appended AFTER the cut, so it always survives. Appendix A: deliberate -- the cap exists to
     * bound spend, and the caller is not told.
     */
    @Test
    void anInputOverTwoHundredThousandCharactersIsCutSilently() throws Exception {
        StringBuilder huge = new StringBuilder();
        while (huge.length() < 250_000) huge.append("0123456789");
        PromptRunner.Job text = job("text");
        text.template = "{{text}}"; text.values.put("text", huge.toString());
        PromptRunner.Job json = job("json");
        json.template = "{{text}}"; json.values.put("text", huge.toString());
        ArgumentCaptor<AiProviderGateway.ChatRequest> sent = ArgumentCaptor.forClass(AiProviderGateway.ChatRequest.class);
        when(this.gateway.chat(sent.capture()))
            .thenReturn(new AiProviderGateway.ChatAnswer("fine", 1, 1))
            .thenReturn(new AiProviderGateway.ChatAnswer("{}", 1, 1));

        AiPromptRun textRow = this.runner.run(text);
        AiPromptRun jsonRow = this.runner.run(json);

        assertThat(sent.getAllValues().get(0).user).hasSize(200_000).isEqualTo(huge.substring(0, 200_000));
        assertThat(sent.getAllValues().get(1).user).startsWith(huge.substring(0, 200_000))
            .endsWith("\n\nAnswer with a JSON object only.")
            .hasSize(200_000 + "\n\nAnswer with a JSON object only.".length());
        assertThat(textRow.getStatus()).isEqualTo("ok");
        assertThat(textRow.getError()).isNull();
        assertThat(textRow.getRenderedInput()).isEqualTo("user: " + huge.substring(0, 200_000));
        assertThat(jsonRow.getStatus()).isEqualTo("ok");
        assertThat(this.runnerLog.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
            .map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList())).isEmpty();
    }
}
