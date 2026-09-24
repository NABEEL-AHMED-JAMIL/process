package process.ai;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import process.model.pojo.AiModelConnection;
import process.model.pojo.AiPromptRun;
import process.model.repository.AiPromptRunRepository;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-159: withCap, the per-connection concurrency cap, is a Semaphore in a map on the
 * PromptRunner instance -- per JVM. Inside one console it holds: maxConcurrency calls in flight,
 * the rest wait in order. Across two console instances nothing is shared, so a connection gets
 * twice its cap. The class javadoc promises "per connection, at most maxConcurrency calls are in
 * flight", which is true only while exactly one console runs.
 */
class PromptRunnerConcurrencyCapTest {

    private final AiProviderGateway gateway = mock(AiProviderGateway.class);
    private final AiPromptRunRepository runs = mock(AiPromptRunRepository.class);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private CountDownLatch entered;

    @BeforeEach
    void setUp() throws Exception {
        when(this.runs.save(any(AiPromptRun.class))).thenAnswer(inv -> inv.getArgument(0));
        this.entered = new CountDownLatch(2);
        when(this.gateway.chat(any())).thenAnswer(inv -> {
            this.entered.countDown();
            this.release.await(10, TimeUnit.SECONDS);
            return new AiProviderGateway.ChatAnswer("answer", 1, 1);
        });
    }

    @AfterEach
    void tearDown() {
        this.release.countDown();
        this.pool.shutdownNow();
    }

    /** maxConcurrency = 1, one connection, the same id on both sides. */
    private static PromptRunner.Job job() {
        AiModelConnection c = new AiModelConnection();
        c.setConnectionId(7L); c.setName("Ollama · shared"); c.setProvider("Ollama"); c.setDefaultModel("gemma3:1b");
        c.setMaxConcurrency(1);
        PromptRunner.Job job = new PromptRunner.Job();
        job.tenantId = 2905L; job.kind = "run"; job.connection = c; job.template = "go"; job.outputMode = "text";
        return job;
    }

    /** One console: a cap of one means the second run waits until the first has answered. */
    @Test
    void withinOneRunnerTheCapHoldsAndTheSecondCallWaits() throws Exception {
        PromptRunner runner = new PromptRunner(this.gateway, this.runs);

        Future<AiPromptRun> first = this.pool.submit(() -> runner.run(job()));
        Future<AiPromptRun> second = this.pool.submit(() -> runner.run(job()));

        assertThat(this.entered.await(500, TimeUnit.MILLISECONDS)).as("only one call may be in flight").isFalse();
        assertThat(this.entered.getCount()).isEqualTo(1);
        this.release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo("ok");
        assertThat(second.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo("ok");
    }

    /**
     * Two consoles -- two PromptRunner instances -- each hold their own semaphore for connection 7,
     * so with a cap of one, two calls are in flight at once. Pinned as today's behaviour; the
     * defect (the cap is per JVM, so N instances give a connection N times its cap) is recorded on
     * MIG-159 for the extracted service to decide.
     */
    @Test
    @Tag("pinned-unreviewed")
    void twoRunnerInstancesGiveOneConnectionTwiceItsCap() throws Exception {
        PromptRunner consoleA = new PromptRunner(this.gateway, this.runs);
        PromptRunner consoleB = new PromptRunner(this.gateway, this.runs);

        Future<AiPromptRun> first = this.pool.submit(() -> consoleA.run(job()));
        Future<AiPromptRun> second = this.pool.submit(() -> consoleB.run(job()));

        assertThat(this.entered.await(5, TimeUnit.SECONDS)).as("both calls in flight on a cap of one").isTrue();
        this.release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo("ok");
        assertThat(second.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo("ok");
    }
}
