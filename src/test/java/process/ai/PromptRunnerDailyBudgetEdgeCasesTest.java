package process.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.jpa.repository.Query;
import process.model.pojo.AiModelConnection;
import process.model.pojo.AiPromptRun;
import process.model.repository.AiPromptRunRepository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-159: the daily token budget's five edge cases, each one asserted.
 *
 * checkBudget sums the tokens recorded against the connection since the start of today and
 * refuses the run when that sum has reached the budget. Appendix A records three of the five as
 * deliberate -- "the daily budget is a THRESHOLD, not a cap, it is not atomic, and the repair
 * round is not re-checked" -- and the other two are what the code and its javadoc say on purpose:
 * the day is the server's own ("the clock every run row is stamped with -- not UTC's"), and the
 * budget belongs to the connection ("per day, once the tokens spent through the connection reach
 * its budget"). An extracted AI service that meters a tenant, or a UTC day, is a behaviour change.
 */
class PromptRunnerDailyBudgetEdgeCasesTest {

    private final AiProviderGateway gateway = mock(AiProviderGateway.class);
    private final AiPromptRunRepository runs = mock(AiPromptRunRepository.class);
    private PromptRunner runner;

    @BeforeEach
    void setUp() {
        when(this.runs.save(any(AiPromptRun.class))).thenAnswer(inv -> inv.getArgument(0));
        this.runner = new PromptRunner(this.gateway, this.runs);
    }

    private static AiModelConnection connection(long id, long budget, int maxConcurrency) {
        AiModelConnection c = new AiModelConnection();
        c.setConnectionId(id); c.setTenantId(2905L); c.setName("connection " + id); c.setProvider("OpenAI");
        c.setDefaultModel("gpt-4.1-mini"); c.setMaxConcurrency(maxConcurrency); c.setDailyTokenBudget(budget);
        return c;
    }

    private static PromptRunner.Job job(AiModelConnection c, String outputMode) {
        PromptRunner.Job job = new PromptRunner.Job();
        job.tenantId = 2905L; job.promptId = 1000L; job.kind = "run"; job.connection = c; job.model = "gpt-4.1-mini";
        job.template = "Summarise."; job.outputMode = outputMode; job.maxTokens = 4096;
        return job;
    }

    // ---- 1. the day is the server's local day, not UTC's -----------------------------------------

    /**
     * The window starts at local midnight in the server's default zone. Run under +14:00 and under
     * -11:00: those two local dates differ from each other at every instant, so a UTC day would
     * disagree with at least one of them -- this fails whatever time of day it runs.
     */
    @Test
    void theBudgetDayStartsAtTheServersLocalMidnightNotUtcs() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String zone : new String[] {"Pacific/Kiritimati", "Pacific/Pago_Pago"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                ArgumentCaptor<Timestamp> since = ArgumentCaptor.forClass(Timestamp.class);
                when(this.runs.tokensSince(eq(7L), since.capture())).thenReturn(0L);
                when(this.gateway.chat(any())).thenReturn(new AiProviderGateway.ChatAnswer("fine", 1, 1));

                this.runner.run(job(connection(7L, 1_000L, 4), "text"));

                ZoneId z = ZoneId.of(zone);
                assertThat(since.getValue().toInstant()).as(zone)
                    .isEqualTo(LocalDate.now(z).atStartOfDay(z).toInstant());
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    // ---- 2. a threshold, not a cap ---------------------------------------------------------------

    /**
     * One token under the budget is enough to make the call, and the call is not limited to what
     * is left: max_tokens goes out as the prompt set it, and the run records every token it spent
     * -- here fifty times the whole budget -- as "ok".
     */
    @Test
    void theBudgetIsAThresholdAndOneRunMaySpendFarPastIt() throws Exception {
        when(this.runs.tokensSince(eq(7L), any())).thenReturn(99L);
        ArgumentCaptor<AiProviderGateway.ChatRequest> sent = ArgumentCaptor.forClass(AiProviderGateway.ChatRequest.class);
        when(this.gateway.chat(sent.capture())).thenReturn(new AiProviderGateway.ChatAnswer("long answer", 4_000, 1_000));

        AiPromptRun row = this.runner.run(job(connection(7L, 100L, 4), "text"));

        assertThat(row.getStatus()).isEqualTo("ok");
        assertThat(row.getTokensIn() + row.getTokensOut()).isEqualTo(5_000);
        assertThat(sent.getValue().maxTokens).isEqualTo(4096);
    }

    // ---- 3. not atomic ---------------------------------------------------------------------------

    /**
     * The check reads the sum, the call happens, and only then is the row saved -- nothing reserves
     * tokens in between. Two runs on the same connection both read "99 of 100", both are let
     * through, and both are in flight at the same moment.
     */
    @Test
    void theBudgetCheckIsNotAtomicTwoRunsBothPassItConcurrently() throws Exception {
        when(this.runs.tokensSince(eq(7L), any())).thenReturn(99L);
        CountDownLatch bothInFlight = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(this.gateway.chat(any())).thenAnswer(inv -> {
            bothInFlight.countDown();
            release.await(10, TimeUnit.SECONDS);
            return new AiProviderGateway.ChatAnswer("answer", 1_000, 0);
        });
        AiModelConnection shared = connection(7L, 100L, 4);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<AiPromptRun>> rows = new ArrayList<>();
            rows.add(pool.submit(() -> this.runner.run(job(shared, "text"))));
            rows.add(pool.submit(() -> this.runner.run(job(shared, "text"))));

            assertThat(bothInFlight.await(5, TimeUnit.SECONDS)).as("both runs passed the budget and reached the provider").isTrue();
            release.countDown();
            for (Future<AiPromptRun> r : rows) assertThat(r.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo("ok");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        verify(this.runs, times(2)).tokensSince(eq(7L), any());
    }

    // ---- 4. the repair round is not re-checked ---------------------------------------------------

    /**
     * The budget is read once, before round one. Round one's own answer here takes the connection
     * far past it, and the repair round goes ahead regardless -- a second read would have refused.
     */
    @Test
    void theRepairRoundIsNotReCheckedAgainstTheBudget() throws Exception {
        when(this.runs.tokensSince(eq(7L), any())).thenReturn(0L, 1_000_000L);
        when(this.gateway.chat(any()))
            .thenReturn(new AiProviderGateway.ChatAnswer("{}", 900, 100))
            .thenReturn(new AiProviderGateway.ChatAnswer("{\"diagnosis\":\"x\"}", 900, 100));
        PromptRunner.Job j = job(connection(7L, 100L, 4), "json");
        j.outputSchema = "{\"required\":[\"diagnosis\"]}";

        AiPromptRun row = this.runner.run(j);

        assertThat(row.getStatus()).isEqualTo("ok");
        verify(this.gateway, times(2)).chat(any());
        verify(this.runs, times(1)).tokensSince(anyLong(), any());
    }

    // ---- 5. scoped by connection, not by tenant --------------------------------------------------

    /**
     * The sum is over the connection's rows, and nothing else: one workspace with its budget spent
     * on connection 7 still runs on connection 8, and the query that feeds the check names no
     * tenant at all.
     */
    @Test
    void theBudgetBelongsToTheConnectionNotTheWorkspace() throws Exception {
        when(this.runs.tokensSince(eq(7L), any())).thenReturn(100L);
        when(this.runs.tokensSince(eq(8L), any())).thenReturn(0L);
        when(this.gateway.chat(any())).thenReturn(new AiProviderGateway.ChatAnswer("fine", 1, 1));

        AiPromptRun spent = this.runner.run(job(connection(7L, 100L, 4), "text"));
        verify(this.gateway, never()).chat(any());
        AiPromptRun fresh = this.runner.run(job(connection(8L, 100L, 4), "text"));

        assertThat(spent.getStatus()).isEqualTo("failed");
        assertThat(fresh.getStatus()).isEqualTo("ok");
        verify(this.gateway, times(1)).chat(any());
        String query = AiPromptRunRepository.class.getMethod("tokensSince", Long.class, Timestamp.class).getAnnotation(Query.class).value();
        assertThat(query).contains("r.connectionId = :connectionId");
        assertThat(query.toLowerCase()).doesNotContain("tenant");
    }
}
