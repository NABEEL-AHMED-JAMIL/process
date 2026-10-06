package process.slo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import process.model.enums.JobStatus;
import process.model.enums.RunEnd;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-196: when process.runs.ended counts a run. Once, as it leaves flight; never for a repeat of a terminal status,
 * a heartbeat, or a write whose transaction rolled back.
 */
class RunOutcomesTest {

    private SimpleMeterRegistry registry;
    private RunOutcomes outcomes;

    @BeforeEach
    void setUp() {
        this.registry = new SimpleMeterRegistry();
        this.outcomes = new RunOutcomes(this.registry);
    }

    @AfterEach
    void clear() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    static double count(SimpleMeterRegistry registry, String outcome, String reason, String slo) {
        Counter counter = registry.find(RunOutcomes.ENDED).tag("outcome", outcome).tag("reason", reason).tag("slo", slo).counter();
        return counter == null ? 0 : counter.count();
    }

    static double total(SimpleMeterRegistry registry) {
        return registry.find(RunOutcomes.ENDED).counters().stream().mapToDouble(Counter::count).sum();
    }

    @Test
    void eachInFlightStatusLeavingForEachTerminalOneIsCountedOnceWithItsTags() {
        for (JobStatus before : JobStatus.IN_FLIGHT) {
            assertThat(this.outcomes.ended(before, JobStatus.Completed, RunEnd.WORKER)).isTrue();
        }
        assertThat(count(this.registry, "completed", "worker", "good")).isEqualTo(3);
        assertThat(this.outcomes.ended(JobStatus.Running, JobStatus.Failed, RunEnd.WORKER)).isTrue();
        assertThat(this.outcomes.ended(JobStatus.Start, JobStatus.Failed, RunEnd.DECLINED)).isTrue();
        assertThat(this.outcomes.ended(JobStatus.Running, JobStatus.Interrupt, RunEnd.STALLED)).isTrue();
        assertThat(this.outcomes.ended(JobStatus.Queue, JobStatus.Failed, RunEnd.REFUSED)).isTrue();
        assertThat(this.outcomes.ended(JobStatus.Running, JobStatus.Interrupt, RunEnd.OPERATOR)).isTrue();
        assertThat(count(this.registry, "failed", "worker", "bad")).isEqualTo(1);
        assertThat(count(this.registry, "failed", "declined", "bad")).isEqualTo(1);
        assertThat(count(this.registry, "interrupt", "stalled", "bad")).isEqualTo(1);
        assertThat(count(this.registry, "failed", "refused", "excluded")).isEqualTo(1);
        assertThat(count(this.registry, "interrupt", "operator", "excluded")).isEqualTo(1);
        assertThat(total(this.registry)).isEqualTo(8);
    }

    @Test
    void aRepeatedTerminalReportAHeartbeatAndAnUnknownRunAreNotCounted() {
        assertThat(this.outcomes.ended(JobStatus.Completed, JobStatus.Completed, RunEnd.WORKER)).isFalse();
        assertThat(this.outcomes.ended(JobStatus.Failed, JobStatus.Failed, RunEnd.WORKER)).isFalse();
        assertThat(this.outcomes.ended(JobStatus.Completed, JobStatus.Interrupt, RunEnd.OPERATOR)).isFalse();
        assertThat(this.outcomes.ended(JobStatus.Running, JobStatus.Running, RunEnd.WORKER)).isFalse();
        assertThat(this.outcomes.ended(JobStatus.Start, JobStatus.Running, RunEnd.WORKER)).isFalse();
        assertThat(this.outcomes.ended(null, JobStatus.Completed, RunEnd.WORKER)).isFalse();
        assertThat(this.outcomes.ended(JobStatus.Running, JobStatus.Completed, null)).isFalse();
        assertThat(total(this.registry)).isZero();
    }

    @Test
    void aRunBornTerminalIsCountedOnceAndOnlyWhenTerminal() {
        assertThat(this.outcomes.born(JobStatus.Skip, RunEnd.SKIPPED)).isTrue();
        assertThat(this.outcomes.born(JobStatus.Missed, RunEnd.MISSED)).isTrue();
        assertThat(this.outcomes.born(JobStatus.Queue, null)).isFalse();
        assertThat(count(this.registry, "skip", "skipped", "excluded")).isEqualTo(1);
        assertThat(count(this.registry, "missed", "missed", "excluded")).isEqualTo(1);
        assertThat(total(this.registry)).isEqualTo(2);
    }

    @Test
    void insideATransactionTheRunIsCountedAtCommitAndNotAtAll() {
        TransactionSynchronizationManager.initSynchronization();
        this.outcomes.ended(JobStatus.Running, JobStatus.Completed, RunEnd.WORKER);
        assertThat(total(this.registry)).as("not before the commit").isZero();
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        TransactionSynchronizationManager.clearSynchronization();
        assertThat(count(this.registry, "completed", "worker", "good")).isEqualTo(1);

        TransactionSynchronizationManager.initSynchronization();
        this.outcomes.ended(JobStatus.Running, JobStatus.Failed, RunEnd.WORKER);
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        TransactionSynchronizationManager.clearSynchronization();
        assertThat(count(this.registry, "failed", "worker", "bad")).as("rolled back: nothing ended").isZero();
    }
}
