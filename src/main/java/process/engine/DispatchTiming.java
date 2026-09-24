package process.engine;

import java.time.Duration;

/**
 * The dispatch timings, written down in one place and derived from one another (MIG-136).
 *
 * Four values used to agree by nobody's design, in three classes: the pass's budget in
 * ProducerBulkEngine, the lock's lockAtMostFor on ProcessCron, the 100 ms per-row pause, and the
 * QUEUE_FETCH_LIMIT fallback. The recorded incident behind them: a slow broker pushed a 5000-row pass
 * past its lock and a second instance dispatched the same rows twice.
 *
 * Re-derived for the phase structure (MIG-134, MIG-25). A row's dispatch is now one local
 * transaction -- the callback token's hash, job_send and a dispatch_outbox row -- with no model call
 * (the pre-dispatch phase answers AI steps first, on its own threads) and no broker call (DispatchRelay
 * publishes after commit). So the longest one row can take is the pause plus that transaction, and the
 * transaction is bounded by its own timeout. The pass checks its budget before each row; the last row
 * it starts therefore ends by DISPATCH_BUDGET + PER_ROW_PAUSE + ROW_TRANSACTION_TIMEOUT, which has to be
 * inside the lock -- DispatchTimingTest asserts exactly that, and that a 5000-row pass stops in time.
 *
 * The pause stays: it paces how fast runs reach the workers, and nothing measured says to change it.
 * At 100 ms a full default fetch of 1000 rows takes under two minutes; 5000 would take 8m20s and is
 * cut at the budget, the rest taken by the next pass in id order.
 */
public final class DispatchTiming {

    /** ProcessCron.startJobInCurrentTimeSlot's lockAtMostFor, in ShedLock's own notation. */
    public static final String DISPATCH_LOCK_AT_MOST_FOR = "10M";

    public static final Duration DISPATCH_LOCK = Duration.ofMinutes(10);

    /** When a pass stops starting rows. */
    public static final long DISPATCH_BUDGET_MS = Duration.ofMinutes(7).toMillis();

    /** Between rows, to pace the hand-off to the workers. */
    public static final long PER_ROW_PAUSE_MS = 100L;

    /** The most one row's dispatch transaction may take before it is rolled back. */
    public static final int ROW_TRANSACTION_TIMEOUT_SECONDS = 30;

    /** How many queued rows one pass takes when orchestration_setting's QUEUE_FETCH_LIMIT cannot be read. */
    public static final long DEFAULT_QUEUE_FETCH_LIMIT = 1000L;

    /**
     * How long the pre-dispatch phase may hold a run while it answers the run's AI steps. Each server
     * step waits at most HttpAi's ten-minute read timeout; three steps is the most a pipeline carries
     * today. A preparer that dies leaves the run to be taken again once this runs out -- the AI service
     * reuses any answer it already recorded for the run.
     */
    public static final Duration PREPARE_LEASE = Duration.ofMinutes(30);

    /** Threads answering AI steps; runs with none are prepared on the pass's own thread. */
    public static final int PREPARE_AI_THREADS = 4;

    /** How many runs one pre-dispatch pass takes. */
    public static final int PREPARE_BATCH = 100;

    private DispatchTiming() {
    }
}
