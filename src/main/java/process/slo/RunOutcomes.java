package process.slo;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import process.model.enums.JobStatus;
import process.model.enums.RunEnd;

import java.util.Locale;

/**
 * The live half of the pipeline execution SLI (MIG-196): {@value #ENDED}, one increment per run, when the run
 * reaches a terminal status, from the code path that wrote it. Tags, all from closed sets:
 * <ul>
 *   <li>{@code outcome}: the terminal status, lower case (completed, failed, interrupt, skip, missed);</li>
 *   <li>{@code reason}: RunEnd, the path that closed it;</li>
 *   <li>{@code slo}: good, bad or excluded, by RunSlo -- the same rule the stored-data report applies.</li>
 * </ul>
 * A run is counted when it LEAVES flight: its row was Queue, Start or Running before the write, or it was written
 * terminal from the start (a skip, a missed slot). So a retried run -- the same row put back to Queue by
 * scheduleRetry, C7c, never passing through a terminal status -- is counted once, at its last attempt; and a
 * repeated terminal report (Completed -> Completed) is not counted again.
 *
 * Counted after the transaction commits, when there is one: a write that rolls back ended nothing.
 */
@Component
public class RunOutcomes {

    public static final String ENDED = "process.runs.ended";

    private final MeterRegistry registry;

    @Autowired
    public RunOutcomes(ObjectProvider<MeterRegistry> registry) {
        this(registry.getIfAvailable(SimpleMeterRegistry::new));
    }

    public RunOutcomes(MeterRegistry registry) {
        this.registry = registry;
    }

    /** For a hand-built BulkAction: counts into a registry nobody reads. */
    public static RunOutcomes detached() {
        return new RunOutcomes(new SimpleMeterRegistry());
    }

    /**
     * A write moved a run from {@code before} to {@code after}. Counts it when that is the run leaving flight.
     * @return whether it was counted
     */
    public boolean ended(JobStatus before, JobStatus after, RunEnd reason) {
        if (before == null || !before.isInFlight() || !RunSlo.isTerminal(after) || reason == null) {
            return false;
        }
        this.count(after, reason);
        return true;
    }

    /** A run written terminal from the start: a skipped or a missed slot. @return whether it was counted */
    public boolean born(JobStatus status, RunEnd reason) {
        if (!RunSlo.isTerminal(status) || reason == null) {
            return false;
        }
        this.count(status, reason);
        return true;
    }

    private void count(JobStatus status, RunEnd reason) {
        Counter counter = Counter.builder(ENDED)
            .description("Runs that reached a terminal status, by status, closing path and SLO class (MIG-196)")
            .tag("outcome", status.name().toLowerCase(Locale.ROOT))
            .tag("reason", reason.tag())
            .tag("slo", RunSlo.of(status, reason).tag())
            .register(this.registry);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
                @Override
                public void afterCommit() {
                    counter.increment();
                }
            });
        } else {
            counter.increment();
        }
    }
}
