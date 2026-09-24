package process.ai;

import org.barco.platform.meter.MeterReporter;
import org.barco.platform.meter.UsageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.ResponseDto;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-159: the AI behaviours that look like bugs and are recorded as deliberate in the defect
 * register's Appendix A (13-defect-register.md; 16-testing-strategy.md §3.2, AI row). Each test
 * quotes why. The extracted AI service must reproduce every one, or say in its own ADR why not.
 *
 * The daily budget's five edge cases are in {@link PromptRunnerDailyBudgetEdgeCasesTest}; the
 * per-JVM concurrency cap in {@link PromptRunnerConcurrencyCapTest}.
 */
class AiAppendixABehavioursTest {

    private final AiProviderGateway gateway = mock(AiProviderGateway.class);
    private final AiPromptRunRepository runs = mock(AiPromptRunRepository.class);
    private final MeterReporter meter = mock(MeterReporter.class);
    private PromptRunner runner;

    @BeforeEach
    void setUp() {
        when(this.runs.save(any(AiPromptRun.class))).thenAnswer(inv -> {
            AiPromptRun row = inv.getArgument(0);
            ReflectionTestUtils.setField(row, "runId", 4242L);
            return row;
        });
        this.runner = new PromptRunner(this.gateway, this.runs);
        ReflectionTestUtils.setField(this.runner, "meter", this.meter);
    }

    private static AiModelConnection connection() {
        AiModelConnection c = new AiModelConnection();
        c.setConnectionId(7L); c.setName("OpenAI · production"); c.setProvider("OpenAI"); c.setDefaultModel("gpt-4.1-mini");
        c.setMaxConcurrency(4);
        return c;
    }

    private static PromptRunner.Job jsonJob(String schema) {
        PromptRunner.Job job = new PromptRunner.Job();
        job.tenantId = 2905L; job.promptId = 1000L; job.kind = "run"; job.connection = connection();
        job.model = "gpt-4.1-mini"; job.template = "Diagnose the claim."; job.outputMode = "json"; job.outputSchema = schema;
        return job;
    }

    private static AiProviderGateway.ProviderException status(int code) {
        return new AiProviderGateway.ProviderException(code, "HTTP " + code + ": provider said no");
    }

    // ---- schema validation is key presence only -------------------------------------------------

    /**
     * Appendix A: "Only key presence is validated, never JSON types." A schema that says total is a
     * number and flags an array is satisfied by a string and an object, first time, no repair round.
     */
    @Test
    void onlyKeyPresenceIsValidatedNeverTheJsonTypes() throws Exception {
        String schema = "{\"type\":\"object\",\"properties\":{\"total\":{\"type\":\"number\"},\"flags\":{\"type\":\"array\"}},"
            + "\"required\":[\"total\",\"flags\"]}";
        when(this.gateway.chat(any())).thenReturn(new AiProviderGateway.ChatAnswer("{\"total\":\"a lot\",\"flags\":{\"x\":true}}", 5, 5));

        AiPromptRun row = this.runner.run(jsonJob(schema));

        assertThat(row.getStatus()).isEqualTo("ok");
        assertThat(row.getOutput()).isEqualTo("{\"total\":\"a lot\",\"flags\":{\"x\":true}}");
        verify(this.gateway, times(1)).chat(any());
    }

    /**
     * When the schema has a required list it is the only thing checked: a property the schema
     * declares but does not require may be missing. Without a required list, every declared
     * property is required.
     */
    @Test
    void theRequiredListWinsOverPropertiesAndPropertiesStandInWhenItIsAbsent() {
        assertThat(this.runner.jsonProblem("{\"a\":1}", "{\"properties\":{\"a\":{},\"b\":{}},\"required\":[\"a\"]}")).isNull();
        assertThat(this.runner.jsonProblem("{\"a\":1}", "{\"properties\":{\"a\":{},\"b\":{}}}")).isEqualTo("missing key(s): b");
        assertThat(this.runner.jsonProblem("[1,2]", "{\"required\":[\"a\"]}")).isEqualTo("not a JSON object");
    }

    /**
     * Appendix A: "A broken output_schema disables key validation rather than failing the run."
     * A schema that does not parse, or parses to something other than an object, validates any
     * JSON object at all -- including one missing every key the author meant. The answer still
     * has to be a JSON object; only the key check goes.
     */
    @Test
    void aBrokenOutputSchemaSilentlyDisablesKeyValidation() throws Exception {
        for (String broken : Arrays.asList("{\"required\": [\"diagnosis\"", "[\"diagnosis\"]", "diagnosis, total")) {
            reset(this.gateway);
            when(this.gateway.chat(any())).thenReturn(new AiProviderGateway.ChatAnswer("{\"unrelated\":1}", 5, 5));

            AiPromptRun row = this.runner.run(jsonJob(broken));

            assertThat(row.getStatus()).as(broken).isEqualTo("ok");
            assertThat(row.getRenderedInput()).as("no keys can be named from it either").endsWith("Answer with a JSON object only.");
            verify(this.gateway, times(1)).chat(any());
        }
        assertThat(this.runner.jsonProblem("not json", "{broken")).isEqualTo("not valid JSON");
    }

    // ---- retries: only 429 and >= 500, so six provider calls is the worst case ------------------

    /**
     * callWithRetry retries only a 429 or a status >= 500, three attempts per round, and a JSON
     * run has two rounds -- so one run can reach the provider SIX times. Counted here with a
     * provider that answers 429, 500, bad JSON, 503, 429, still-bad JSON. The run fails, and is
     * still billed for the two answers it paid for (Appendix A: "a failed prompt run is still
     * billed for both rounds"): the usage events go out for a failed run. Takes eight seconds of
     * the retry sleeps (1 s then 3 s, per round), which is the behaviour, not a test artefact.
     */
    @Test
    void theWorstCaseIsSixProviderCallsForOneRunAndTheFailureIsStillBilled() throws Exception {
        when(this.gateway.chat(any()))
            .thenThrow(status(429)).thenThrow(status(500))
            .thenReturn(new AiProviderGateway.ChatAnswer("{}", 100, 10))
            .thenThrow(status(503)).thenThrow(status(429))
            .thenReturn(new AiProviderGateway.ChatAnswer("{\"other\":1}", 200, 20))
            .thenReturn(new AiProviderGateway.ChatAnswer("{\"diagnosis\":\"a seventh call would have fixed it\"}", 1, 1));

        AiPromptRun row = this.runner.run(jsonJob("{\"required\":[\"diagnosis\"]}"));

        verify(this.gateway, times(6)).chat(any());
        assertThat(row.getStatus()).isEqualTo("failed");
        assertThat(row.getAttempts()).as("the repair round's attempt count, not the run's total").isEqualTo(3);
        assertThat(row.getTokensIn()).isEqualTo(300);
        assertThat(row.getTokensOut()).isEqualTo(30);
        ArgumentCaptor<UsageEvent> events = ArgumentCaptor.forClass(UsageEvent.class);
        verify(this.meter, atLeast(0)).report(events.capture());
        assertThat(events.getAllValues()).extracting(e -> e.quantity).containsExactly(300.0, 30.0);
    }

    /**
     * Anything that is not a 429 or a 5xx fails at the first attempt -- a 408 or a 499 included,
     * which a client might think transient. A 500 is retried: the boundary is >= 500, not > 500.
     */
    @Test
    void onlyA429OrAStatusOfFiveHundredOrMoreIsRetried() throws Exception {
        for (int code : new int[] {400, 401, 403, 404, 408, 409, 422, 499}) {
            reset(this.gateway);
            when(this.gateway.chat(any())).thenThrow(status(code)).thenReturn(new AiProviderGateway.ChatAnswer("{}", 1, 1));

            AiPromptRun row = this.runner.run(jsonJob(null));

            assertThat(row.getStatus()).as("HTTP " + code).isEqualTo("failed");
            verify(this.gateway, times(1)).chat(any());
        }
        reset(this.gateway);
        when(this.gateway.chat(any())).thenThrow(status(500)).thenReturn(new AiProviderGateway.ChatAnswer("{}", 1, 1));
        assertThat(this.runner.run(jsonJob(null)).getStatus()).isEqualTo("ok");
        verify(this.gateway, times(2)).chat(any());
    }

    // ---- AiStepService idempotency: 'ok' is reused, 'failed' is deleted and retried --------------

    /**
     * Idempotency is on (job_queue_id, step_tag) -- the partial unique index ux_ai_prompt_run_step --
     * with a deliberate asymmetry. A recorded "ok" answer is reused as it stands, so a queue row
     * dispatched twice pays once. A recorded "failed" row is DELETED and the step runs again, since
     * the index would otherwise refuse the new row and the retry is the point of dispatching again.
     */
    @Test
    void anOkStepIsReusedButAFailedOneIsDeletedAndRunAgain() {
        PipelineRepository pipelines = mock(PipelineRepository.class);
        AiPromptRepository prompts = mock(AiPromptRepository.class);
        AiModelConnectionRepository connections = mock(AiModelConnectionRepository.class);
        PromptRunner stepRunner = mock(PromptRunner.class);
        Pipeline pipeline = new Pipeline();
        pipeline.setPipelineId("F1"); pipeline.setTenantId(2905L); pipeline.setStatus(Status.Active);
        PipelineField step = new PipelineField();
        step.setTagKey("summary"); step.setFieldType("ai"); step.setPosition(0); step.setPromptId(1000L); step.setOnError("fail");
        pipeline.getFields().add(step);
        AiPrompt prompt = new AiPrompt();
        prompt.setPromptId(1000L); prompt.setTenantId(2905L); prompt.setStatus(Status.Active); prompt.setVersion(2);
        prompt.setName("Summarise"); prompt.setUserTemplate("Summarise."); prompt.setOutputMode("text");
        AiModelConnection conn = connection(); conn.setStatus(Status.Active);
        when(pipelines.findAllByPipelineIdAndTenantIdAndStatusNot("F1", 2905L, Status.Delete)).thenReturn(Collections.singletonList(pipeline));
        when(prompts.findById(1000L)).thenReturn(Optional.of(prompt));
        when(connections.findFirstByTenantIdAndIsDefaultTrueAndStatus(2905L, Status.Active)).thenReturn(Optional.of(conn));
        AiPromptRun fresh = new AiPromptRun(); fresh.setStatus("ok"); fresh.setOutput("new answer");
        when(stepRunner.run(any())).thenReturn(fresh);
        AiStepService steps = new AiStepService(pipelines, prompts, connections, this.runs, mock(EncryptionUtil.class), stepRunner);

        AiPromptRun ok = new AiPromptRun(); ok.setStatus("ok"); ok.setOutput("paid for already");
        when(this.runs.findByJobQueueIdAndStepTag(55L, "summary")).thenReturn(Optional.of(ok));
        AiStepService.Outcome reused = steps.apply(2905L, "F1", 55L, "<pipeline/>");
        assertThat(reused.payload).contains("<summary>paid for already</summary>");
        verify(this.runs, never()).delete(any());
        verify(stepRunner, never()).run(any());

        AiPromptRun failed = new AiPromptRun(); failed.setStatus("failed"); failed.setError("HTTP 500");
        when(this.runs.findByJobQueueIdAndStepTag(55L, "summary")).thenReturn(Optional.of(failed));
        AiStepService.Outcome retried = steps.apply(2905L, "F1", 55L, "<pipeline/>");
        assertThat(retried.payload).contains("<summary>new answer</summary>");
        InOrder order = inOrder(this.runs, stepRunner);
        order.verify(this.runs).delete(failed);
        order.verify(stepRunner).run(any());
    }

    // ---- every SSRF refusal reads the same ------------------------------------------------------

    /**
     * Appendix A: "All SSRF refusals share one message so the answer carries no map of the
     * network." A private address, loopback, the cloud metadata address, carrier-grade NAT, an IPv6
     * unique-local address, plain http to a host nobody allow-listed and a name that does not
     * resolve are one sentence -- a caller cannot tell "exists but internal" from "does not exist".
     * Only a malformed URL, which reveals nothing about the network, is worded differently.
     */
    @Test
    void everyNetworkRefusalIsTheSameSentence() {
        AiEndpointPolicy policy = new AiEndpointPolicy();
        ReflectionTestUtils.setField(policy, "allowedEndpointHosts", "host.docker.internal");
        List<String> said = Arrays.asList("https://10.0.0.5/v1", "https://127.0.0.1/v1", "https://169.254.169.254/latest/meta-data",
                "https://100.64.0.1/v1", "https://[fd00::1]/v1", "http://api.example.com/v1", "https://no-such-host.invalid/v1")
            .stream().map(policy::validateEndpoint).map(ResponseDto::getMessage).collect(Collectors.toList());

        assertThat(said).containsOnly("That apiEndpoint is not an allowed AI provider address.");
        assertThat(policy.validateEndpoint("ftp://files.example.com/x").getMessage()).isEqualTo("apiEndpoint must be an http or https URL naming a host.");
    }
}
