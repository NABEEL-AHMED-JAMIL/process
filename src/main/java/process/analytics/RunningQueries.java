package process.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import process.security.TenantContext;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Every analytics run currently in flight, so that one can be stopped.
 *
 * Until this existed the only thing that could stop a query was the server's own timeout watchdog,
 * and a user watching a scan they already knew was wrong had no button. The mechanism is not new:
 * the watchdog worked by calling {@link Statement#cancel()}, measured to interrupt a CPU-bound
 * DuckDB query at about 1006ms with "INTERRUPT Error: Interrupted!", and it worked because
 * setQueryTimeout is a NO-OP in duckdb_jdbc 1.1.3 so nothing else could. This class is that same
 * cancel() reached from a second direction, which is why the watchdog now calls it through a
 * {@link Handle} rather than holding a Statement of its own. One cancel path, two callers.
 *
 * <b>The tenancy rule is the reason this is a class and not a map.</b> A registry keyed by id and
 * nothing else is a cross-tenant denial of service: one workspace guessing or replaying another's
 * id could stop its queries at will, and against a governor ceiling of four permits, stopping
 * queries is most of what an attacker would want. So every entry records the tenant AND the user
 * who started it, and {@link #cancel(String)} compares both against the caller's own context
 * before it touches anything. A run belonging to somebody else is answered exactly as a run that
 * finished a moment ago -- see cancel() for why that sameness is deliberate.
 *
 * <b>Nothing may outlive its run.</b> An entry is removed by {@link Handle#close()} in a finally,
 * so completion, engine failure, timeout, user cancellation and a refusal that never got a permit
 * all remove it by the same line. A map that grows forever would be a memory leak with a tenant
 * id, a user id and a live JDBC handle in every entry.
 *
 * <b>Why this holds a java.sql.Statement.</b> It is the handle DuckDB's driver gives us, and
 * pretending otherwise -- a Cancellable interface over one implementation -- would be an
 * abstraction with nothing on the other side of it. Any JDBC engine fits this unchanged; an
 * engine that is not JDBC would bring its own handle type and this class would be generalised
 * then, with a second implementation to check the shape against. {@link AnalyticsEngine}'s javadoc
 * says the same thing from the other end.
 *
 * @author Nabeel Ahmed
 */
@Component
public class RunningQueries {

    private static final Logger logger = LoggerFactory.getLogger(RunningQueries.class);

    /**
     * What a caller-supplied run id is allowed to look like.
     *
     * A run id becomes a map key, a log field and a sentence returned to a user, so it is
     * restricted rather than trusted. Length is capped because nothing needs a long one and an
     * unbounded string from a request body ends up in a log line at whatever size it was sent.
     */
    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** What became of a cancel request. Two outcomes on purpose; see cancel(). */
    public enum Outcome {
        /** The run was found, belonged to the caller, and has been interrupted. */
        CANCELLED,
        /** There is no such run of the caller's to stop. Not an error. */
        NOT_RUNNING
    }

    private final Map<String, Handle> inFlight = new ConcurrentHashMap<>();

    /**
     * Registers a run before it has anything to cancel, and returns the handle that owns it.
     *
     * Opened BEFORE the governor permit is taken, so the two seconds a caller may spend waiting
     * for a slot are a state a user can see and stop rather than a gap in the record. That is
     * what makes QUEUED a real state here and not a constant nobody writes.
     *
     * @param requestedId an id the caller chose, or null to mint one
     * @throws AnalyticsException if the id is malformed, or is already in flight -- a caller that
     *         reuses an id while the first run is going is asking two questions with one name,
     *         and the honest answer is to say so rather than to silently cancel the wrong one
     */
    public Handle open(String requestedId) throws AnalyticsException {
        String id = requestedId == null || requestedId.trim().isEmpty()
            ? UUID.randomUUID().toString() : requestedId.trim();
        if (!RUN_ID.matcher(id).matches()) {
            throw new AnalyticsException("A query id may be up to 64 letters, digits, hyphens or "
                + "underscores.");
        }
        Handle handle = new Handle(id, TenantContext.getTenantId(), TenantContext.getAppUserId());
        // Keyed on the CALLER and the id, never the id alone.
        //
        // A single global map keyed on a client-supplied id is the same membership oracle that
        // cancel() below spends a paragraph closing, entered through the front door instead: with
        // one namespace, "A query with that id is already running." answers the question "is this
        // id live in somebody else's workspace?", and it answers it to anyone who can call /query
        // with an id of their choosing. The id MUST be client-chosen for cancellation to work at
        // all on a synchronous endpoint, so those ids are predictable by construction.
        //
        // The second effect was worse than the leak: a stranger holding id X made the rightful
        // owner's own run with X fail, and the owner could not clear it, because cancel() -- quite
        // correctly -- refuses to touch a handle that is not theirs. A denial of service on a
        // namespace, delivered by the one part of the class that was trying to be honest.
        if (this.inFlight.putIfAbsent(handle.key(), handle) != null) {
            throw new AnalyticsException("A query with that id is already running.");
        }
        return handle;
    }

    /**
     * Stops the caller's own run, if it is still going.
     *
     * <b>A run that is not the caller's is answered as though it were not there.</b> The
     * alternative -- "that query belongs to another workspace" -- is a membership oracle: it
     * confirms an id exists and that somebody else is running it, which is exactly what a caller
     * probing for other tenants' work wants to learn. The attempt is logged instead, at warn,
     * because a caller sending another tenant's id is worth an operator's attention even though
     * the answer they get is a shrug.
     *
     * <b>Cancelling an already-finished query is not an error.</b> It is the normal race: a user
     * presses stop as the last row lands. NOT_RUNNING says so, the endpoint returns success, and
     * the history row keeps whatever the run actually did.
     */
    public Outcome cancel(String runId) {
        if (runId == null || !RUN_ID.matcher(runId.trim()).matches()) {
            return Outcome.NOT_RUNNING;
        }
        // Looked up under the CALLER's key, so another workspace's run is not merely refused
        // below -- it is not found at all, which is the same answer as an id that never existed.
        Handle handle = this.inFlight.get(
            keyFor(TenantContext.getTenantId(), TenantContext.getAppUserId(), runId.trim()));
        if (handle == null) {
            return Outcome.NOT_RUNNING;
        }
        if (!handle.belongsToCaller()) {
            // Names both halves of the identity, because the two refusals mean different things
            // to whoever reads this line: a different TENANT is somebody reaching across a
            // workspace boundary, and a different USER inside the same tenant is usually a
            // colleague trying to stop a query they can see and do not own.
            logger.warn("Tenant {} user {} asked to cancel analytics query {}, which belongs to "
                + "tenant {} user {}. Refused.", TenantContext.getTenantId(),
                TenantContext.getAppUserId(), runId.trim(), handle.tenantId, handle.appUserId);
            return Outcome.NOT_RUNNING;
        }
        return handle.stop(Stopper.USER) ? Outcome.CANCELLED : Outcome.NOT_RUNNING;
    }

    /**
     * The registry key: who is running it, and what they called it.
     *
     * A null tenant is a platform admin, and null is a legitimate part of the identity rather than
     * a missing one -- two platform admins are told apart by the user half. Written once and used
     * by open, cancel and close, because a key computed three ways is a leak waiting for the
     * fourth caller.
     */
    private static String keyFor(Long tenantId, Long appUserId, String runId) {
        return tenantId + ":" + appUserId + ":" + runId;
    }

    /**
     * How many runs are in flight.
     *
     * Exists for the assertion that this map empties -- a registry that leaks is the defect this
     * class is most likely to have, and it is invisible from every other observation.
     */
    public int size() {
        return this.inFlight.size();
    }

    /** Who asked for a run to stop. The two produce different lifecycle states and different words. */
    enum Stopper {
        USER,
        TIMEOUT
    }

    /**
     * One run's place in the registry, and the only thing allowed to interrupt it.
     *
     * The lock discipline is inherited from the watchdog it replaces, and the reason is the
     * driver's own: cancel() on a closed connection is not safe. Every state change and every
     * cancel happens inside {@code synchronized (this)}, and the execution path marks the run
     * finished under that same lock BEFORE the statement closes -- so a cancel already running
     * finishes first, and one that has not started never will.
     */
    public final class Handle {

        private final String id;
        private final Long tenantId;
        private final Long appUserId;
        private final long startedAt = System.currentTimeMillis();

        /** Null until the run holds a permit and has a statement open. */
        private Statement statement;

        private AnalyticsEngine.RunState state = AnalyticsEngine.RunState.QUEUED;
        private boolean finished;
        private Stopper stoppedBy;

        /** This run's place in the registry. Same three parts, every time. */
        private String key() {
            return keyFor(this.tenantId, this.appUserId, this.id);
        }

        private Handle(String id, Long tenantId, Long appUserId) {
            this.id = id;
            this.tenantId = tenantId;
            this.appUserId = appUserId;
        }

        public String getId() {
            return this.id;
        }

        public synchronized AnalyticsEngine.RunState getState() {
            return this.state;
        }

        /** Who stopped this run, or null if nobody did. Read after the engine throws. */
        synchronized Stopper stoppedBy() {
            return this.stoppedBy;
        }

        public long elapsedMs() {
            return System.currentTimeMillis() - this.startedAt;
        }

        /**
         * Attaches the statement this run will be cancelled through, moving it QUEUED to RUNNING.
         *
         * Returns false when a cancel landed in the window between the permit and the statement --
         * the one moment a stop request has nothing to act on. Without this the run would proceed
         * to completion after its owner had been told it was cancelled, which is worse than either
         * outcome on its own.
         */
        synchronized boolean running(Statement statement) {
            if (this.stoppedBy != null) {
                return false;
            }
            this.statement = statement;
            this.state = AnalyticsEngine.RunState.RUNNING;
            return true;
        }

        /**
         * Interrupts the run, if there is still one to interrupt.
         *
         * A run that is still QUEUED has no statement, so the flag alone stops it: the execution
         * path checks it after the permit and refuses to start. A run that failed to cancel keeps
         * neither the flag nor the claim -- rolling back inside the lock is what stops a query
         * that went on to return rows being reported to its owner as cancelled.
         *
         * <b>One race is left open deliberately.</b> An interrupt that lands in the last
         * millisecond of a query it did not manage to stop leaves the caller told "stopped" and
         * then handed a complete result. Closing it would mean discarding an answer the engine had
         * already produced, on the grounds that somebody asked to stop waiting for it; a result
         * arriving after the stop is the smaller surprise, and it is a true one.
         */
        synchronized boolean stop(Stopper stopper) {
            if (this.finished || this.stoppedBy != null) {
                return false;
            }
            this.stoppedBy = stopper;
            if (this.statement == null) {
                return true;
            }
            try {
                this.statement.cancel();
                return true;
            } catch (SQLException ex) {
                this.stoppedBy = null;
                // Nothing to tell the user beyond the answer they are about to get. This line is
                // for whoever asks why the query kept running after they pressed stop.
                logger.warn("Could not interrupt analytics query {}: {}", this.id, ex.getMessage());
                return false;
            }
        }

        /**
         * Marks the run over so that no cancel can reach a statement that is about to close.
         *
         * Called inside the try-with-resources, before the statement is closed, and idempotent so
         * that close() can call it again on the paths that never got this far.
         */
        synchronized void finish() {
            this.finished = true;
        }

        /**
         * Ends the run and takes it out of the registry.
         *
         * The only removal, and it is called from a finally, so completion, failure, timeout,
         * cancellation and a caller who never got a permit all leave by this line.
         */
        public void close() {
            this.finish();
            RunningQueries.this.inFlight.remove(this.key(), this);
        }

        /**
         * Whether the caller is the tenant and the user that started this run.
         *
         * Both halves are required to be present. A null tenant matching a null tenant is the
         * shape of the bug DatasetResolver was fixed for -- a row with no owner visible to
         * everyone -- and a registry is no place to repeat it.
         *
         * A platform admin is deliberately NOT given a pass. Cancelling is a write against
         * somebody else's work, and the role that can see every tenant's data does not need to be
         * the role that can stop every tenant's queries; if operations ever needs that, it should
         * be a named endpoint with its own audit line rather than a quiet clause here.
         */
        private boolean belongsToCaller() {
            Long callerTenant = TenantContext.getTenantId();
            Long callerUser = TenantContext.getAppUserId();
            return this.tenantId != null && callerTenant != null && this.tenantId.equals(callerTenant)
                && this.appUserId != null && callerUser != null && this.appUserId.equals(callerUser);
        }
    }
}
