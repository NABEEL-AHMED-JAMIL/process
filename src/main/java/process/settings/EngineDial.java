package process.settings;

import java.util.Optional;

/**
 * Owner #24 (2026-10-19): the engine settings a platform administrator may change on Platform › Engine settings -- an
 * allow-list, and nothing else. Each has a range the server enforces, a default (the environment's value when it sets
 * one, else the built-in), what it is measured in, when a change takes effect, and whether a workspace may have its own
 * value (an override, only where one workspace's value cannot hurt another's: its own runs' limits, never a pool every
 * workspace shares).
 *
 * <p>Secrets, URLs, keys, ports and the like are never here: they stay in the environment and are not editable (a
 * setting that is not in this list cannot be written, whatever its name).
 */
public enum EngineDial {

    QUEUE_FETCH_LIMIT("Runs claimed per dispatch pass", "How many queued runs one dispatch pass claims and hands to workers.",
        "runs", 1, 1_000_000, 1000, null, false, "At the next dispatch pass (within seconds)."),
    PIPELINE_MAX_STEPS("Steps a pipeline may have", "The most steps one pipeline definition may hold; a longer one is refused when it "
        + "is saved and when a run starts.", "steps", 1, 500, 100, "process.pipeline.max-steps", true,
        "When a pipeline is next saved or run."),
    STEP_MAX_TIMEOUT_SECONDS("Longest step timeout", "The longest timeout a pipeline's settings or one of its steps may ask for.",
        "seconds", 60, 86_400, 86_400, null, true, "When a pipeline is next saved or run."),
    RUN_MEMORY_MB("Memory budget per run", "What the rows one run's steps hold at once may take; past it a streaming step spills to "
        + "disk and an in-memory step fails in words.", "MB", 16, 4096, 128, "process.pipeline.run-memory-mb", true,
        "At the next run."),
    BATCH_ROWS("Rows a streaming step reads at a time", "The batch a streaming step reads from a dataset between memory checks.",
        "rows", 64, 100_000, 1024, "process.pipeline.batch-rows", false, "At the next run."),
    ENGINE_THREADS("Runs at once", "How many runs the step engine works on at the same time, every workspace's together.",
        "runs", 1, 64, 4, "process.pipeline.engine.threads", false, "After Core restarts (its next deploy)."),
    BACKFILL_MAX_OPEN("Open backfills per workspace", "How many backfills (running or paused) one workspace may have at once.",
        "backfills", 1, 50, 3, "process.backfill.max-open", true, "When a backfill is next started."),
    BACKFILL_MAX_IN_FLIGHT("Backfill runs in flight", "Backfill runs in flight on the platform at once, every workspace's together.",
        "runs", 1, 64, 4, "process.backfill.max-in-flight", false, "At the backfill dispatcher's next pass (within a minute)."),
    BACKFILL_MEMORY_MB("Memory for one backfill", "Shared by one backfill's runs in flight: with the per-run budget it sets how many "
        + "of its runs may go at once (at most 4).", "MB", 64, 8192, 512, "process.backfill.memory-mb", false,
        "When a backfill is next started.");

    private final String label;
    private final String description;
    private final String unit;
    private final long min;
    private final long max;
    private final long builtIn;
    private final String property;
    private final boolean perWorkspace;
    private final String takesEffect;

    EngineDial(String label, String description, String unit, long min, long max, long builtIn, String property, boolean perWorkspace,
        String takesEffect) {
        this.label = label;
        this.description = description;
        this.unit = unit;
        this.min = min;
        this.max = max;
        this.builtIn = builtIn;
        this.property = property;
        this.perWorkspace = perWorkspace;
        this.takesEffect = takesEffect;
    }

    public String key() { return this.name(); }

    public String label() { return this.label; }

    public String description() { return this.description; }

    public String unit() { return this.unit; }

    public long min() { return this.min; }

    public long max() { return this.max; }

    /** The value when neither the environment nor a platform administrator set one. */
    public long builtIn() { return this.builtIn; }

    /** The environment property whose value is the default; null when only the built-in is. */
    public String property() { return this.property; }

    /** Whether a workspace may have a value of its own. */
    public boolean perWorkspace() { return this.perWorkspace; }

    public String takesEffect() { return this.takesEffect; }

    /** The value clamped into the range: what the engine uses, whatever was stored. */
    public long clamp(long value) {
        return Math.max(this.min, Math.min(this.max, value));
    }

    /**
     * The value a person typed, or why it is refused: digits only (no "5,000", no spaces, no decimals), inside the range.
     */
    public Optional<String> problem(String typed) {
        if (typed == null || !typed.matches("[0-9]{1,10}")) {
            return Optional.of(String.format("%s is a whole number from %,d to %,d, written as digits only. Got \"%s\".", this.label,
                this.min, this.max, typed));
        }
        long value = Long.parseLong(typed);
        if (value < this.min || value > this.max) {
            return Optional.of(String.format("%s is from %,d to %,d %s. Got %,d.", this.label, this.min, this.max, this.unit, value));
        }
        return Optional.empty();
    }

    public static Optional<EngineDial> of(String key) {
        if (key == null) {
            return Optional.empty();
        }
        for (EngineDial dial : values()) {
            if (dial.name().equals(key.trim())) {
                return Optional.of(dial);
            }
        }
        return Optional.empty();
    }
}
