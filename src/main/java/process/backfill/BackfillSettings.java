package process.backfill;

import org.springframework.beans.factory.annotation.Autowired;
import process.settings.EngineDial;
import process.settings.EngineDials;
import org.springframework.stereotype.Component;

/**
 * MIG-347: a backfill's limits -- since owner #24 (2026-10-19) Platform › Engine settings' values, with these properties as
 * their defaults.
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
    private final int memoryMb;
    private final int maxInFlight;
    private final int runMemoryMb;
    /** Owner #24: Platform › Engine settings; null for fixed values (tests, defaults()). */
    private final EngineDials dials;

    /** Owner #24: the values are Platform › Engine settings' (their defaults the process.backfill.* properties), read on use. */
    @Autowired
    public BackfillSettings(EngineDials dials) {
        this.dials = dials;
        this.maxOpen = 0;
        this.memoryMb = 0;
        this.maxInFlight = 0;
        this.runMemoryMb = 0;
    }

    public BackfillSettings(int maxOpen, int memoryMb, int runMemoryMb, int maxInFlight) {
        this.dials = null;
        this.maxOpen = Math.max(1, maxOpen);
        this.memoryMb = memoryMb;
        this.maxInFlight = Math.max(1, maxInFlight);
        this.runMemoryMb = Math.max(16, runMemoryMb);
    }

    /** The platform's per-run memory budget. */
    public int runMemoryMb() {
        return this.dials == null ? this.runMemoryMb : Math.max(16, this.dials.platformInt(EngineDial.RUN_MEMORY_MB));
    }

    public static BackfillSettings defaults() {
        return new BackfillSettings(3, 512, 128, 4);
    }

    /** Open backfills the platform allows a workspace by default. */
    public int maxOpen() {
        return this.maxOpen(null);
    }

    /** Open backfills this workspace may have at once: its own value, else the platform's. */
    public int maxOpen(Long tenantId) {
        return this.dials == null ? this.maxOpen : Math.max(1, this.dials.forWorkspaceInt(EngineDial.BACKFILL_MAX_OPEN, tenantId));
    }

    /** The most runs one backfill may have in flight: the memory budget's share, at most the job's 4 lanes. */
    public int maxConcurrency() {
        int memory = this.dials == null ? this.memoryMb : this.dials.platformInt(EngineDial.BACKFILL_MEMORY_MB);
        return Math.max(1, Math.min(LANES, memory / this.runMemoryMb()));
    }

    public int maxInFlight() {
        return this.dials == null ? this.maxInFlight : Math.max(1, this.dials.platformInt(EngineDial.BACKFILL_MAX_IN_FLIGHT));
    }

    public int defaultConcurrency() {
        return Math.min(DEFAULT_CONCURRENCY, this.maxConcurrency());
    }
}
