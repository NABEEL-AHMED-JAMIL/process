package process.pipeline.tasks;

import org.barco.platform.correlation.CorrelationId;
import org.barco.platform.correlation.CorrelationScope;
import org.barco.platform.tenancy.RowSecurity;
import process.pipeline.registry.JsonSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MIG-418 "Calls at once" (owner 2026-10-21): a per-row step's calls -- an AI prompt per row, a transcription per file, a
 * Call API per row or batch, a report per row -- made a few at a time instead of one after another. Opt in per step
 * ({@code callsAtOnce}, default 1: one after another, on the step's own thread, exactly as before).
 *
 * <ul>
 *   <li><b>Capped twice.</b> A step makes at most {@link #MAX_PER_STEP} at once, whatever it asks; and every call holds one
 *   of its workspace's permits ({@link #workspaceCap}, {@code process.pipeline.calls-at-once.workspace}, 8 by default), so
 *   all the steps and runs of one workspace on this Core together never have more calls in flight. Another workspace's
 *   calls never wait on these.</li>
 *   <li><b>In row order.</b> Answers come back in the order of the rows, whatever order the calls finish in, so a step keeps
 *   its output, its row numbers and its errors as before; a call that fails stops new calls from starting (the step then
 *   handles the first failure in row order, by its own rules).</li>
 *   <li><b>Backs off when busy.</b> A call answered busy (429 or 503, {@link Busy}) waits -- its Retry-After, else 1, 2, 4,
 *   8 s -- and is made again, at most {@link #BUSY_TRIES} times; meanwhile the step's own limit halves (never below 1), so a
 *   busy service sees fewer calls from it.</li>
 *   <li><b>Metered as before.</b> Each call is the same call to the same service, which meters it as it always has: nothing is
 *   counted here.</li>
 * </ul>
 * The calls run in the run's workspace (RowSecurity) and under its correlation id, as the step's own thread does.
 */
final class AtOnce {

    static final int MAX_PER_STEP = 8;
    static final int BUSY_TRIES = 4;
    static final String SETTING = "callsAtOnce";

    /** The setting the four per-row steps take, as the Task Registry shows it. */
    static JsonSchema setting(String what) {
        return JsonSchema.integer().minimum(1).maximum(MAX_PER_STEP).title("Calls at once").defaultValue(1)
            .description(what + " at once (at most " + MAX_PER_STEP + "; the workspace's calls together are capped too). "
                + "1: one after another. Answers keep the rows' order; a busy service is backed off.");
    }

    /** A call answered busy (429, 503): made again after {@code retryAfterSeconds} (0: the back-off's own wait). */
    static final class Busy extends Exception {
        final int retryAfterSeconds;

        Busy(String message, int retryAfterSeconds) {
            super(message, null, false, false);
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    /** One call, by its row's index; may throw {@link Busy}. */
    @FunctionalInterface
    interface Call<R> {
        R call(int index) throws Exception;
    }

    /** One row's outcome: its answer, or why not, or that it was not made (a call before it failed). */
    static final class Outcome<R> {
        final R value;
        final Exception error;
        final boolean made;

        private Outcome(R value, Exception error, boolean made) {
            this.value = value;
            this.error = error;
            this.made = made;
        }
    }

    private static volatile int workspaceCap = 8;
    private static final Map<Long, Semaphore> WORKSPACES = new ConcurrentHashMap<>();
    /** How long a back-off waits a second (tests shorten it). */
    static volatile long secondMillis = 1000L;

    private AtOnce() {
    }

    /** Set from Core's settings at start; a workspace's permits are made at its first call. */
    static void workspaceCap(int cap) {
        workspaceCap = Math.max(1, cap);
        WORKSPACES.clear();
    }

    static int workspaceCap() {
        return workspaceCap;
    }

    /** Permits a workspace has free now (tests). */
    static int free(long tenantId) {
        return permits(tenantId).availablePermits();
    }

    private static Semaphore permits(long tenantId) {
        return WORKSPACES.computeIfAbsent(tenantId, t -> new Semaphore(workspaceCap, true));
    }

    /** The step's setting, within 1..MAX_PER_STEP. */
    static int of(Map<String, Object> config) {
        return Math.max(1, Math.min(MAX_PER_STEP, Configs.integer(config, SETTING, 1)));
    }

    /**
     * Makes {@code count} calls, {@code atOnce} at a time, and answers them in index order. With {@code atOnce} 1 they are made
     * on this thread one after another and the first failure stops the rest, as a plain loop would.
     */
    static <R> List<Outcome<R>> run(long tenantId, int count, int atOnce, Call<R> call) throws InterruptedException {
        return run(tenantId, count, atOnce, true, call);
    }

    /** As above; with {@code stopOnError} false a row's failure is only that row's (a step that skips or continues). */
    static <R> List<Outcome<R>> run(long tenantId, int count, int atOnce, boolean stopOnError, Call<R> call) throws InterruptedException {
        List<Outcome<R>> out = new ArrayList<>(Collections.nCopies(count, (Outcome<R>) null));
        if (atOnce <= 1 || count <= 1) {
            for (int i = 0; i < count; i++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("The step was stopped.");
                }
                try {
                    out.set(i, new Outcome<>(call.call(i), null, true));
                } catch (InterruptedException stopped) {
                    throw stopped;
                } catch (Exception failed) {
                    out.set(i, new Outcome<>(null, failed, true));
                    if (stopOnError) {
                        fillNotMade(out);
                        return out;
                    }
                }
            }
            return out;
        }
        String correlation = CorrelationId.current();
        Semaphore workspace = permits(tenantId);
        AtomicInteger allowed = new AtomicInteger(Math.min(atOnce, MAX_PER_STEP));
        AtomicInteger inFlight = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        Object turn = new Object();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(atOnce, MAX_PER_STEP), r -> {
            Thread t = new Thread(r, "step-calls-" + tenantId);
            t.setDaemon(true);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count && !stop.get(); i++) {
                synchronized (turn) {
                    while (inFlight.get() >= allowed.get() && !stop.get()) {
                        turn.wait(200);
                    }
                    if (stop.get()) {
                        break;
                    }
                    inFlight.incrementAndGet();
                }
                int index = i;
                futures.add(pool.submit(() -> {
                    try {
                        Outcome<R> outcome = RowSecurity.forTenant(tenantId, () -> one(workspace, correlation, index, call, allowed, turn));
                        synchronized (out) {
                            out.set(index, outcome);
                        }
                        if (outcome.error != null && (stopOnError || outcome.error instanceof InterruptedException)) {
                            stop.set(true);
                        }
                    } finally {
                        synchronized (turn) {
                            inFlight.decrementAndGet();
                            turn.notifyAll();
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (InterruptedException stopped) {
            futures.forEach(f -> f.cancel(true));
            throw stopped;
        } catch (ExecutionException impossible) {
            throw new IllegalStateException(impossible.getCause());
        } finally {
            pool.shutdownNow();
        }
        fillNotMade(out);
        return out;
    }

    private static <R> Outcome<R> one(Semaphore workspace, String correlation, int index, Call<R> call, AtomicInteger allowed, Object turn) {
        CorrelationScope scope = CorrelationScope.open(correlation == null ? CorrelationId.generate() : correlation);
        try {
            for (int attempt = 1; ; attempt++) {
                long wait;
                workspace.acquire();
                try {
                    return new Outcome<>(call.call(index), null, true);
                } catch (Busy busy) {
                    if (attempt >= BUSY_TRIES) {
                        return new Outcome<>(null, new IllegalStateException(busy.getMessage() + " (still busy after "
                            + BUSY_TRIES + " tries)"), true);
                    }
                    synchronized (turn) {
                        allowed.set(Math.max(1, allowed.get() / 2));
                    }
                    wait = busy.retryAfterSeconds > 0 ? busy.retryAfterSeconds : 1L << (attempt - 1);
                } finally {
                    workspace.release();
                }
                // The permit is given back while waiting: another of the workspace's calls may go meanwhile.
                Thread.sleep(Math.min(60, wait) * secondMillis);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return new Outcome<>(null, stopped, true);
        } catch (Exception failed) {
            return new Outcome<>(null, failed, true);
        } finally {
            scope.close();
        }
    }

    private static <R> void fillNotMade(List<Outcome<R>> out) {
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) == null) {
                out.set(i, new Outcome<>(null, null, false));
            }
        }
    }

    /** Waits for a workspace's permits to be all free (tests). */
    static boolean idle(long tenantId, long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (free(tenantId) == workspaceCap) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        return free(tenantId) == workspaceCap;
    }
}
