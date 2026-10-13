package process.backfill;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MIG-347: a backfill's limits.
 *
 * <ul>
 *   <li>{@code process.backfill.max-open} (3): open backfills (running or paused) a workspace may have at once.</li>
 *   <li>{@code process.backfill.memory-mb} (512) over {@code process.pipeline.run-memory-mb} (128, a run's memory budget,
 *   ADR-024): how many of one backfill's runs may be in flight at once -- at most 4, the lanes a job has (V211).</li>
 *   <li>{@code process.backfill.max-in-flight} (4): backfill runs in flight on the platform at once, every workspace's
 *   together, so a backfill never holds more of the step engine's threads ({@code process.pipeline.engine.threads}, 4) than
 *   this; the job's live runs share the same threads.</li>
 * </ul>
 */
@Component
public class BackfillSettings {

    public static final int LANES = 4;
    public static final int DEFAULT_CONCURRENCY = 2;

    private final int maxOpen;
    private final int maxConcurrency;
    private final int maxInFlight;
    private final int runMemoryMb;

    public BackfillSettings(@Value("${process.backfill.max-open:3}") int maxOpen, @Value("${process.backfill.memory-mb:512}") int memoryMb,
        @Value("${process.pipeline.run-memory-mb:128}") int runMemoryMb, @Value("${process.backfill.max-in-flight:4}") int maxInFlight) {
        this.maxOpen = Math.max(1, maxOpen);
        this.maxConcurrency = Math.max(1, Math.min(LANES, memoryMb / Math.max(16, runMemoryMb)));
        this.maxInFlight = Math.max(1, maxInFlight);
        this.runMemoryMb = Math.max(16, runMemoryMb);
    }

    public int runMemoryMb() {
        return this.runMemoryMb;
    }

    public static BackfillSettings defaults() {
        return new BackfillSettings(3, 512, 128, 4);
    }

    public int maxOpen() {
        return this.maxOpen;
    }

    /** The most runs one backfill may have in flight: the memory budget's share, at most the job's 4 lanes. */
    public int maxConcurrency() {
        return this.maxConcurrency;
    }

    public int maxInFlight() {
        return this.maxInFlight;
    }

    public int defaultConcurrency() {
        return Math.min(DEFAULT_CONCURRENCY, this.maxConcurrency);
    }
}
