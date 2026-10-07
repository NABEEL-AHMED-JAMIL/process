package process.pipeline.data;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A run's memory budget (MIG-344, {@code process.pipeline.run-memory-mb}): what the rows a run's steps hold at once may
 * take. A streaming step holds a batch of its input; a step that needs more (an aggregate's groups) asks for it with
 * {@link #tryReserve} and spills to disk when the answer is no. So a run's rows stay within the budget however big its
 * files are, and {@code engine.threads} runs x the budget is what the engine's rows can take of the heap.
 *
 * The bytes are estimates of the rows' heap size ({@link #estimate}), within about 20% of what a heap dump shows for
 * text cells (measured in BigDataBench's phases: about 110 bytes a cell). The peak and what was spilled are reported on
 * the step's log.
 */
public final class RunMemory {

    public static final int DEFAULT_MB = 128;

    private final long budget;
    private final AtomicLong used = new AtomicLong();
    private final AtomicLong peak = new AtomicLong();
    private final AtomicLong spilled = new AtomicLong();

    public RunMemory(long budgetBytes) {
        this.budget = Math.max(1L << 20, budgetBytes);
    }

    public static RunMemory ofMegabytes(long mb) {
        return new RunMemory(mb * 1024L * 1024L);
    }

    public long budget() {
        return this.budget;
    }

    /** Takes {@code bytes} of the budget if they fit; false (and nothing taken) when they do not. */
    public boolean tryReserve(long bytes) {
        while (true) {
            long now = this.used.get();
            if (now + bytes > this.budget) {
                return false;
            }
            if (this.used.compareAndSet(now, now + bytes)) {
                this.peak.accumulateAndGet(now + bytes, Math::max);
                return true;
            }
        }
    }

    /** Takes {@code bytes} whether they fit or not: what a batch in hand already holds. */
    public void reserve(long bytes) {
        this.peak.accumulateAndGet(this.used.addAndGet(bytes), Math::max);
    }

    public void release(long bytes) {
        this.used.addAndGet(-bytes);
    }

    public long used() {
        return this.used.get();
    }

    /** The most held at once since the last {@link #resetPeak}. */
    public long peak() {
        return this.peak.get();
    }

    /** Starts a new peak (each step reports its own). */
    public void resetPeak() {
        this.peak.set(this.used.get());
        this.spilled.set(0);
    }

    public void spilled(long bytes) {
        this.spilled.addAndGet(bytes);
    }

    /** Bytes written to disk because they did not fit, since the last {@link #resetPeak}. */
    public long spilled() {
        return this.spilled.get();
    }

    /** A row's heap size, roughly: the map and its entries, and each value. */
    public static long estimate(Map<String, Object> row) {
        long bytes = 64 + 16L * row.size();
        for (Object value : row.values()) {
            bytes += 32 + estimate(value);
        }
        return bytes;
    }

    /** A value's heap size, roughly. */
    public static long estimate(Object value) {
        if (value == null || value instanceof Boolean) {
            return 0;
        }
        if (value instanceof String) {
            return 40 + ((String) value).length();
        }
        if (value instanceof Number) {
            return 24;
        }
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            return estimate(map);
        }
        if (value instanceof List) {
            long bytes = 40;
            for (Object item : (List<?>) value) {
                bytes += 8 + estimate(item);
            }
            return bytes;
        }
        return 64;
    }

    /** A batch's heap size, roughly. */
    public static long estimate(List<Map<String, Object>> batch) {
        long bytes = 40 + 8L * batch.size();
        for (Map<String, Object> row : batch) {
            bytes += estimate(row);
        }
        return bytes;
    }

    public static String megabytes(long bytes) {
        return String.format("%,.1f MB", bytes / (1024.0 * 1024.0));
    }
}
