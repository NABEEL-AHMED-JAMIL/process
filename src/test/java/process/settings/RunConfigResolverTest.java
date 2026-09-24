package process.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.PipelineConfig;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.repository.JobQueueRepository;
import process.model.repository.PipelineConfigRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.security.RunCallbackTokens;
import process.util.EncryptionUtil;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-167: a worker fetches the configuration its run's task references, at run time, proven by the run's own
 * X-Worker-Token. Core answers only that run, only while it is Start or Running, only from that run's workspace, and
 * only the keys that run's task references, as the kind it references them; every secret handed out is written to the
 * run's audit log by key name; and no value reaches any log line.
 */
class RunConfigResolverTest {

    private static final String TOKEN = "cbt_1.9.run-token";
    private static final String CANARY = PipelineConfigServiceTest.CANARY;
    private static final long MINE = 2905L;
    private static final long THEIRS = 2901L;
    private static final String PAYLOAD = "<pipeline><bucket>${config:INPUT_BUCKET}</bucket>"
        + "<db_password>${secret:DB_PASSWORD}</db_password></pipeline>";

    private final RunCallbackTokens tokens = mock(RunCallbackTokens.class);
    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final PipelineConfigRepository entries = mock(PipelineConfigRepository.class);
    private final TransactionServiceImpl audit = mock(TransactionServiceImpl.class);
    private final EncryptionUtil encryption = PipelineConfigServiceTest.sealing();
    private LogCapture logs;
    private RunConfigResolver resolver;
    private JobQueue run;

    @BeforeEach
    void setUp() {
        this.resolver = new RunConfigResolver(this.tokens, this.runs, this.jobs, this.entries, this.encryption, this.audit);
        this.run = new JobQueue();
        this.run.setJobQueueId(9L);
        this.run.setJobId(7L);
        this.run.setJobStatus(JobStatus.Running);
        when(this.runs.findById(9L)).thenReturn(Optional.of(this.run));
        when(this.tokens.verify(7L, 9L, TOKEN)).thenReturn(Optional.empty());
        when(this.tokens.verify(eq(7L), eq(9L), eq("someone-elses"))).thenReturn(Optional.of(RunCallbackTokens.Refusal.MISMATCH));
        SourceTask task = new SourceTask();
        task.setTaskPayload(PAYLOAD);
        task.setTenantId(MINE);
        SourceJob job = new SourceJob();
        job.setJobId(7L);
        job.setTenantId(MINE);
        job.setTaskDetail(task);
        when(this.jobs.findById(7L)).thenReturn(Optional.of(job));
        when(this.entries.findForRun(eq(MINE), anyCollection())).thenReturn(Arrays.asList(
            value(MINE, "INPUT_BUCKET", "etl-inputs"), this.secret(MINE, "DB_PASSWORD", CANARY)));
        when(this.entries.findForRun(eq(THEIRS), anyCollection())).thenReturn(Arrays.asList(
            value(THEIRS, "INPUT_BUCKET", "their-bucket"), this.secret(THEIRS, "DB_PASSWORD", "their-password")));
        this.logs = new LogCapture();
    }

    @AfterEach
    void detach() {
        this.logs.close();
    }

    @Test
    void aLiveRunGetsWhatItsTaskReferencesFromItsOwnWorkspace() {
        ResponseEntity<ResponseDto> answer = this.resolve(TOKEN, list("INPUT_BUCKET"), list("DB_PASSWORD"));

        assertThat(answer.getStatusCodeValue()).isEqualTo(200);
        assertThat(answer.getBody().getStatus()).isEqualTo("SUCCESS");
        Map<String, Map<String, String>> data = data(answer);
        assertThat(data.get("config")).containsExactly(entry("INPUT_BUCKET", "etl-inputs"));
        assertThat(data.get("secrets")).containsExactly(entry("DB_PASSWORD", CANARY));
        verify(this.entries, never()).findForRun(eq(THEIRS), anyCollection());
    }

    @Test
    void everySecretReadIsAuditedOnTheRunByKeyNameOnly() {
        this.resolve(TOKEN, list("INPUT_BUCKET"), list("DB_PASSWORD"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> lines = ArgumentCaptor.forClass(List.class);
        verify(this.audit).saveJobAuditLogs(eq(7L), eq(9L), lines.capture());
        assertThat(lines.getValue()).containsExactly("Secret DB_PASSWORD was read by the worker.");
        assertThat(String.join("\n", lines.getValue())).doesNotContain(CANARY);
    }

    @Test
    void valuesAreNeverLogged() {
        this.resolve(TOKEN, list("INPUT_BUCKET"), list("DB_PASSWORD"));
        this.resolve("someone-elses", list("INPUT_BUCKET"), list("DB_PASSWORD"));
        this.resolve(TOKEN, list("OUTPUT_BUCKET"), list("DB_PASSWORD"));

        String everything = this.logs.everything();
        assertThat(everything).contains("DB_PASSWORD").doesNotContain(CANARY).doesNotContain("etl-inputs")
            .doesNotContain(TOKEN).doesNotContain("kp2026a:");
    }

    @Test
    void anotherRunsTokenIsRefusedBeforeAnythingIsRead() {
        ResponseEntity<ResponseDto> answer = this.resolve("someone-elses", list("INPUT_BUCKET"), list("DB_PASSWORD"));

        assertThat(answer.getStatusCodeValue()).isEqualTo(401);
        assertThat(answer.getBody().getMessage()).isEqualTo("Unauthorized worker callback.");
        assertThat(answer.getBody().getData()).isNull();
        verify(this.entries, never()).findForRun(anyLong(), anyCollection());
        verify(this.audit, never()).saveJobAuditLogs(anyLong(), anyLong(), anyList());
    }

    @Test
    void onlyAStartedOrRunningRunIsAnswered() {
        for (JobStatus status : JobStatus.values()) {
            this.run.setJobStatus(status);
            int expected = status == JobStatus.Start || status == JobStatus.Running ? 200 : 401;
            assertThat(this.resolve(TOKEN, list("INPUT_BUCKET"), list()).getStatusCodeValue()).as(status.name()).isEqualTo(expected);
        }
    }

    @Test
    void aKeyTheRunsTaskDoesNotReferenceIsABadRequest() {
        ResponseEntity<ResponseDto> other = this.resolve(TOKEN, list("OUTPUT_BUCKET"), list());
        ResponseEntity<ResponseDto> otherKind = this.resolve(TOKEN, list("DB_PASSWORD"), list());
        ResponseEntity<ResponseDto> secretAsConfig = this.resolve(TOKEN, list(), list("INPUT_BUCKET"));

        for (ResponseEntity<ResponseDto> answer : Arrays.asList(other, otherKind, secretAsConfig)) {
            assertThat(answer.getStatusCodeValue()).isEqualTo(400);
            assertThat(answer.getBody().getData()).isNull();
        }
        assertThat(other.getBody().getMessage()).contains("OUTPUT_BUCKET").contains("not referenced");
        verify(this.entries, never()).findForRun(anyLong(), anyCollection());
    }

    @Test
    void aMalformedRequestIsABadRequest() {
        assertThat(this.resolver.resolve(TOKEN, null, 9L, list("INPUT_BUCKET"), list()).getStatusCodeValue()).isEqualTo(400);
        assertThat(this.resolve(TOKEN, list(), list()).getStatusCodeValue()).isEqualTo(400);
        assertThat(this.resolve(TOKEN, list("input_bucket"), list()).getStatusCodeValue()).isEqualTo(400);
    }

    @Test
    void aReferencedKeyThatIsGoneFailsTheRunWithASentence() {
        when(this.entries.findForRun(eq(MINE), anyCollection())).thenReturn(Collections.singletonList(value(MINE, "INPUT_BUCKET", "x")));

        ResponseEntity<ResponseDto> answer = this.resolve(TOKEN, list("INPUT_BUCKET"), list("DB_PASSWORD"));

        assertThat(answer.getStatusCodeValue()).isEqualTo(200);
        assertThat(answer.getBody().getStatus()).isEqualTo("ERROR");
        assertThat(answer.getBody().getMessage()).isEqualTo("Configuration DB_PASSWORD is not set in this workspace.");
        assertThat(answer.getBody().getData()).isNull();
        verify(this.audit, never()).saveJobAuditLogs(anyLong(), anyLong(), anyList());
    }

    /** Stored with the other kind -- a VALUE where the task says ${secret:...} -- is not what the task asked for. */
    @Test
    void anEntryOfTheOtherKindIsNotSet() {
        when(this.entries.findForRun(eq(MINE), anyCollection())).thenReturn(Arrays.asList(
            value(MINE, "INPUT_BUCKET", "x"), value(MINE, "DB_PASSWORD", "plain")));

        ResponseEntity<ResponseDto> answer = this.resolve(TOKEN, list("INPUT_BUCKET"), list("DB_PASSWORD"));

        assertThat(answer.getBody().getStatus()).isEqualTo("ERROR");
        assertThat(answer.getBody().getMessage()).contains("DB_PASSWORD").doesNotContain("plain");
    }

    /** Belt and braces: a row of another workspace, however it arrived, is never handed out. */
    @Test
    void aRowOfAnotherWorkspaceIsNeverHandedOut() {
        when(this.entries.findForRun(eq(MINE), anyCollection())).thenReturn(Arrays.asList(
            value(MINE, "INPUT_BUCKET", "x"), this.secret(THEIRS, "DB_PASSWORD", "their-password")));

        ResponseEntity<ResponseDto> answer = this.resolve(TOKEN, list("INPUT_BUCKET"), list("DB_PASSWORD"));

        assertThat(answer.getBody().getStatus()).isEqualTo("ERROR");
        assertThat(String.valueOf(answer.getBody().getData())).doesNotContain("their-password");
    }

    private ResponseEntity<ResponseDto> resolve(String token, List<String> config, List<String> secrets) {
        return this.resolver.resolve(token, 7L, 9L, config, secrets);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> data(ResponseEntity<ResponseDto> answer) {
        return (Map<String, Map<String, String>>) answer.getBody().getData();
    }

    private static List<String> list(String... keys) {
        return Arrays.asList(keys);
    }

    static PipelineConfig value(long tenant, String key, String value) {
        PipelineConfig entry = new PipelineConfig();
        entry.setTenantId(tenant);
        entry.setConfigKey(key);
        entry.setKind("VALUE");
        entry.setValue(value);
        entry.setCreatedAt(new Timestamp(0));
        return entry;
    }

    PipelineConfig secret(long tenant, String key, String plain) {
        PipelineConfig entry = new PipelineConfig();
        entry.setTenantId(tenant);
        entry.setConfigKey(key);
        entry.setKind("SECRET");
        entry.setValueSealed(this.encryption.encrypt(plain));
        entry.setCreatedAt(new Timestamp(0));
        return entry;
    }
}
