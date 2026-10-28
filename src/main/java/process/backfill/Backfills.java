package process.backfill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import process.util.ListSort;
import process.billing.BillingPort;
import process.billing.WorkspaceZones;
import process.engine.ProducerBulkEngine;
import process.pipeline.PipelineDefinition;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.RunParameters;
import process.security.JobOwnership;
import process.security.TenantContext;
import process.security.TenantOwnership;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MIG-347: backfills -- a schedule run once for each period (day, hour or week) of a past range, a few at a time.
 *
 * <ul>
 *   <li><b>Preview</b> (nothing written): the periods in the workspace's billing zone, which already have a completed run
 *   (skipped unless "re-run completed" is asked), the parameter values the runs take, the cost estimate (the pipeline's
 *   average run cost over the last 30 days x the periods that will run; administrators only), and every refusal start
 *   would give.</li>
 *   <li><b>Start</b>: the backfill and one period row each; the dispatcher (BackfillDispatcher) queues their runs.</li>
 *   <li><b>Pause, resume, cancel, retry failed</b>; a list and a backfill's detail with its progress, ETA and (for
 *   administrators) what its runs were billed so far.</li>
 * </ul>
 * Who may: whoever may run the schedule -- its workspace, and a tenant user only their own schedules (JobOwnership); the
 * console's endpoints sit under sourceJob.json, Schedules' page. An API client acts for its workspace.
 */
@Component
public class Backfills {

    private static final Logger logger = LoggerFactory.getLogger(Backfills.class);

    static final List<String> OPEN = Arrays.asList("RUNNING", "PAUSED");
    static final List<String> STATUSES = Arrays.asList("RUNNING", "PAUSED", "CANCELLED", "COMPLETED", "FAILED");
    static final int SLOTS_PAGE = 100;

    private final BackfillStore store;
    private final PipelineDefinitionStore definitions;
    private final WorkspaceZones zones;
    private final BillingPort billing;
    private final BackfillSettings settings;
    private final TransactionTemplate tx;
    private final Clock clock;
    /** A workspace's pause (Suspended, Inactive), or empty: ProducerBulkEngine's local view. */
    private final Function<Long, Optional<String>> workspacePause;

    @Autowired
    public Backfills(BackfillStore store, PipelineDefinitionStore definitions, WorkspaceZones zones, BillingPort billing,
        BackfillSettings settings, PlatformTransactionManager transactions, ProducerBulkEngine engine) {
        this(store, definitions, zones, billing, settings, new TransactionTemplate(transactions), Clock.systemUTC(), engine::workspacePause);
    }

    public Backfills(BackfillStore store, PipelineDefinitionStore definitions, WorkspaceZones zones, BillingPort billing, BackfillSettings settings,
        TransactionTemplate tx, Clock clock, Function<Long, Optional<String>> workspacePause) {
        this.store = store;
        this.definitions = definitions;
        this.zones = zones;
        this.billing = billing;
        this.settings = settings;
        this.tx = tx;
        this.clock = clock;
        this.workspacePause = workspacePause;
    }

    // -------------------------------------------------------------------------------------------------------- shapes

    /** What a person or an API client asks for. */
    public static final class Request {
        public Long jobId;
        public String from;
        public String to;
        public String step;
        public Integer concurrency;
        public Boolean rerunCompleted;
        public Map<String, Object> parameters;
    }

    /** Who starts it: the console (a person) or the API (a client). */
    public static final class Origin {
        final String via;
        final Long userId;
        final String clientId;

        private Origin(String via, Long userId, String clientId) {
            this.via = via;
            this.userId = userId;
            this.clientId = clientId;
        }

        public static Origin console(Long userId) {
            return new Origin("console", userId, null);
        }

        public static Origin api(String clientId) {
            return new Origin("api", null, clientId);
        }

        boolean isApi() {
            return "api".equals(this.via);
        }
    }

    /** A refusal: its HTTP status, the customer API's problem kind, and the sentence a person reads. */
    public static final class Refused extends RuntimeException {
        private final HttpStatus status;
        private final String kind;

        public Refused(HttpStatus status, String kind, String message) {
            super(message);
            this.status = status;
            this.kind = kind;
        }

        public HttpStatus getStatus() {
            return this.status;
        }

        public String getKind() {
            return this.kind;
        }
    }

    static Refused notFound(String what) {
        return new Refused(HttpStatus.NOT_FOUND, "not-found", what);
    }

    static Refused invalid(String message) {
        return new Refused(HttpStatus.BAD_REQUEST, "invalid-backfill", message);
    }

    static Refused conflict(String kind, String message) {
        return new Refused(HttpStatus.CONFLICT, kind, message);
    }

    /** The periods and values a request comes to. */
    static final class Plan {
        BackfillStore.Job job;
        BackfillPeriods.Step step;
        LocalDate from;
        LocalDate to;
        ZoneId zone;
        int concurrency;
        boolean rerun;
        List<BackfillPeriods.Period> periods;
        Set<Instant> covered;
        List<PipelineDefinition.Parameter> declared;
        RunParameters.Resolved values;

        int willRun() {
            return this.rerun ? this.periods.size() : this.periods.size() - this.covered.size();
        }
    }

    /** A cost estimate, or why there is none. */
    static final class Estimate {
        BigDecimal perRun;
        BigDecimal total;
        String currency;
        String basis;
        String unavailable;

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("perRun", this.perRun);
            out.put("total", this.total);
            out.put("currency", this.currency);
            out.put("basis", this.basis);
            out.put("unavailable", this.unavailable);
            return out;
        }
    }

    // ------------------------------------------------------------------------------------------------------- reading

    /** Whether this caller reads costs: a workspace administrator or the platform's (billing's own rule). */
    static boolean readsCosts() {
        String role = TenantContext.getUserRole();
        return "TENANT_ADMIN".equals(role) || "PLATFORM_ADMIN".equals(role);
    }

    /** A caller with no workspace (and not the platform's administrator) has no backfills: refused, never "every workspace". */
    static void requireWorkspace() {
        Long tenant = TenantContext.getTenantId();
        if (!TenantContext.isPlatformAdmin() && (tenant == null || tenant <= 0)) {
            throw new Refused(HttpStatus.BAD_REQUEST, "no-workspace", "Backfills belong to a workspace; this account is not attached to one.");
        }
    }

    /** The schedule, when the caller may run it; else not found (the same answer, as everywhere). */
    BackfillStore.Job visibleJob(Long jobId, boolean api) {
        requireWorkspace();
        if (jobId == null || jobId <= 0) {
            throw invalid("Name the schedule to backfill.");
        }
        Optional<BackfillStore.Job> job = this.store.job(jobId);
        if (!job.isPresent() || "Delete".equals(job.get().status) || !TenantOwnership.isOwnedByCaller(job.get().tenantId)
            || (!api && !JobOwnership.namesCaller(job.get().createdBy, job.get().assignedUserId))) {
            throw notFound("No such schedule.");
        }
        return job.get();
    }

    /** The backfill, when the caller may see it (its schedule is theirs). */
    BackfillStore.Backfill visible(Long backfillId, boolean api) {
        requireWorkspace();
        if (backfillId == null || backfillId <= 0) {
            throw invalid("Name the backfill.");
        }
        Optional<BackfillStore.Backfill> found = this.store.find(backfillId);
        if (!found.isPresent() || !TenantOwnership.isOwnedByCaller(found.get().tenantId)
            || (!api && !JobOwnership.namesCaller(found.get().jobCreatedBy, found.get().jobAssignedUserId))) {
            throw notFound("No such backfill.");
        }
        return found.get();
    }

    /** The request's periods, values and limits, as start would take them; every refusal start would give but the caps. */
    Plan plan(Request request, boolean api) {
        if (request == null) {
            throw invalid("Say what to backfill: a schedule, a first and a last day.");
        }
        Plan plan = new Plan();
        plan.job = this.visibleJob(request.jobId, api);
        if (!"Active".equals(plan.job.status)) {
            throw conflict("pipeline-inactive", "The schedule is switched off; switch it on to backfill it.");
        }
        PipelineDefinition definition = this.definitionOf(plan.job);
        plan.declared = definition.effectiveParameters();
        plan.step = request.step == null || request.step.trim().isEmpty() ? BackfillPeriods.Step.DAILY
            : BackfillPeriods.Step.of(request.step).orElseThrow(() -> invalid("The step is daily, hourly or weekly."));
        try {
            plan.from = LocalDate.parse(String.valueOf(request.from).trim());
            plan.to = LocalDate.parse(String.valueOf(request.to).trim());
        } catch (DateTimeParseException wrong) {
            throw invalid("Give the first and the last day as dates, e.g. 2026-10-01.");
        }
        plan.zone = this.zones.zoneOf(plan.job.tenantId);
        Instant now = this.clock.instant();
        LocalDate today = LocalDate.ofInstant(now, plan.zone);
        if (plan.to.isAfter(today)) {
            throw invalid(String.format("A backfill is for the past: the range ends %s, after today (%s in %s).", plan.to, today, plan.zone.getId()));
        }
        try {
            plan.periods = BackfillPeriods.of(plan.from, plan.to, plan.step, plan.zone, now);
        } catch (IllegalArgumentException wrong) {
            throw invalid(wrong.getMessage());
        }
        if (plan.periods.isEmpty()) {
            throw invalid("That range has no period that has begun yet.");
        }
        int max = this.settings.maxConcurrency();
        plan.concurrency = request.concurrency == null ? this.settings.defaultConcurrency() : request.concurrency;
        if (plan.concurrency < 1 || plan.concurrency > max) {
            throw invalid(String.format("Concurrency is 1 to %d here: each run has a memory budget of %d MB, and a backfill's runs share "
                + "the step engine with every other run.", max, this.settings.runMemoryMb()));
        }
        plan.rerun = Boolean.TRUE.equals(request.rerunCompleted);
        plan.values = RunParameters.resolve(plan.declared, request.parameters);
        if (!plan.values.ok()) {
            throw new Refused(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-parameters", "The runs' parameters do not stand: " + plan.values.sentence());
        }
        // ZT-P8: which periods completed before is said either way; re-running them only changes what runs.
        plan.covered = this.store.covered(plan.job.jobId, plan.periods);
        return plan;
    }

    /** The job's pipeline's latest step definition; a refusal for a job without one (no worker runs a legacy pipeline now). */
    PipelineDefinition definitionOf(BackfillStore.Job job) {
        if (job.pipelineId == null || job.pipelineId.trim().isEmpty()) {
            throw conflict("pipeline-not-backfillable", "This schedule's pipeline has no steps; only a pipeline with steps can be backfilled.");
        }
        Optional<PipelineDefinitionStore.Stored> stored = this.definitions.latestFor(job.tenantId, job.pipelineId);
        PipelineDefinition definition = null;
        try {
            definition = stored.isPresent() ? stored.get().definition() : null;
        } catch (IllegalStateException unreadable) {
            definition = null;
        }
        if (definition == null || definition.isLegacy()) {
            throw conflict("pipeline-not-backfillable", "This schedule's pipeline has no steps; only a pipeline with steps can be backfilled.");
        }
        return definition;
    }

    /** What start would do, and refuse: nothing is written. */
    public Map<String, Object> preview(Request request) {
        Plan plan = this.plan(request, false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobId", plan.job.jobId);
        out.put("jobName", plan.job.name);
        out.put("zone", plan.zone.getId());
        out.put("step", plan.step.name());
        out.put("from", plan.from.toString());
        out.put("to", plan.to.toString());
        out.put("periods", plan.periods.size());
        out.put("alreadyCompleted", plan.covered.size());
        out.put("willRun", plan.willRun());
        out.put("rerunCompleted", plan.rerun);
        out.put("concurrency", plan.concurrency);
        out.put("maxConcurrency", this.settings.maxConcurrency());
        out.put("maxPeriods", BackfillPeriods.MAX_PERIODS);
        List<Map<String, Object>> dates = new ArrayList<>(plan.periods.size());
        for (BackfillPeriods.Period p : plan.periods) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("date", p.date.toString());
            d.put("start", p.start.toString());
            d.put("end", p.end.toString());
            d.put("completed", plan.covered.contains(p.start));
            dates.add(d);
        }
        out.put("dates", dates);
        out.put("parameters", plan.declared);
        out.put("values", plan.values.values);
        List<String> warnings = new ArrayList<>();
        Optional<Object[]> held = this.store.heldElsewhere(plan.job.jobId, starts(plan), null);
        held.ifPresent(h -> warnings.add(String.format("%s is already in backfill #%d of this schedule; start refuses a range that overlaps an "
            + "open backfill.", h[1], (Long) h[0])));
        int open = this.store.openCount(plan.job.tenantId);
        if (open >= this.settings.maxOpen(plan.job.tenantId)) {
            warnings.add(String.format("This workspace has %d open backfills, the most it may have at once; wait for one to finish or cancel one.",
                open));
        }
        if (plan.willRun() == 0) {
            warnings.add("Every period of this range already has a completed run; tick Re-run completed periods to run them again.");
        }
        this.workspacePause.apply(plan.job.tenantId).ifPresent(p -> warnings.add("This workspace is " + p
            + ", so its runs are paused: the backfill waits until it is Active again."));
        out.put("warnings", warnings);
        out.put("openBackfills", open);
        out.put("maxOpen", this.settings.maxOpen(plan.job.tenantId));
        if (readsCosts()) {
            out.put("estimate", this.estimate(plan.job, plan.willRun()).toMap());
        } else {
            out.put("estimate", null);
            out.put("estimateNote", "Cost estimates are shown to workspace administrators.");
        }
        return out;
    }

    private static List<Instant> starts(Plan plan) {
        return plan.periods.stream().map(p -> p.start).collect(Collectors.toList());
    }

    // ------------------------------------------------------------------------------------------------------ starting

    /** Starts it: the backfill and its periods, written together; the dispatcher queues the runs. */
    public Map<String, Object> start(Request request, Origin origin) {
        Plan plan = this.plan(request, origin.isApi());
        if (plan.willRun() == 0) {
            throw conflict("nothing-to-run", "Every period of this range already has a completed run; ask to re-run completed periods to "
                + "run them again.");
        }
        // Asked of billing before the transaction: a slow answer holds no row.
        Estimate estimate = this.estimate(plan.job, plan.willRun());
        Long id;
        try {
            id = this.tx.execute(status -> {
                this.store.lockWorkspace(plan.job.tenantId);
                int open = this.store.openCount(plan.job.tenantId);
                if (open >= this.settings.maxOpen(plan.job.tenantId)) {
                    throw conflict("too-many-backfills", String.format("This workspace has %d open backfills, the most it may have at once. Wait "
                        + "for one to finish, or cancel one.", open));
                }
                Optional<Object[]> held = this.store.heldElsewhere(plan.job.jobId, starts(plan), null);
                if (held.isPresent()) {
                    throw conflict("backfill-overlaps", String.format("%s is already in backfill #%d of this schedule, which is still open; "
                        + "pick a range after it, or cancel that one.", held.get()[1], (Long) held.get()[0]));
                }
                BackfillStore.Backfill b = new BackfillStore.Backfill();
                b.tenantId = plan.job.tenantId;
                b.jobId = plan.job.jobId;
                b.step = plan.step.name();
                b.from = plan.from;
                b.to = plan.to;
                b.zone = plan.zone.getId();
                b.concurrency = plan.concurrency;
                b.rerunCompleted = plan.rerun;
                b.params = plan.values.values.isEmpty() ? null : new RunParameters.RunValues(plan.values.values, null, null, null, null).toJson();
                b.total = plan.periods.size();
                b.estimatePerRun = estimate.perRun;
                b.estimateTotal = estimate.total;
                b.estimateCurrency = estimate.currency;
                b.estimateBasis = estimate.basis == null ? cut(estimate.unavailable, 300) : cut(estimate.basis, 300);
                b.createdBy = origin.userId;
                b.createdVia = origin.via;
                b.clientId = origin.clientId;
                b.dateCreated = this.clock.instant();
                return this.store.insert(b, plan.periods);
            });
        } catch (DuplicateKeyException raced) {
            throw conflict("backfill-overlaps", "Another backfill of this schedule took some of these periods just now; read the backfills again.");
        }
        logger.info("Backfill {} of job {} started by {} {}: {} {} period(s) {}..{} in {}, concurrency {}.", id, plan.job.jobId, origin.via,
            origin.isApi() ? origin.clientId : origin.userId, plan.periods.size(), plan.step, plan.from, plan.to, plan.zone, plan.concurrency);
        return this.describe(this.store.find(id).orElseThrow(() -> notFound("No such backfill.")), false);
    }

    // ------------------------------------------------------------------------------------------------------ changing

    public Map<String, Object> pause(Long backfillId, Long by, boolean api) {
        return this.change(backfillId, api, b -> {
            if (!this.store.move(b.id, Collections.singletonList("RUNNING"), "PAUSED", by)) {
                throw conflict("backfill-not-running", "Only a running backfill can be paused; this one is " + words(b.status) + ".");
            }
        });
    }

    public Map<String, Object> resume(Long backfillId, boolean api) {
        return this.change(backfillId, api, b -> {
            if (!this.store.move(b.id, Collections.singletonList("PAUSED"), "RUNNING", null)) {
                throw conflict("backfill-not-paused", "Only a paused backfill can be resumed; this one is " + words(b.status) + ".");
            }
        });
    }

    /** Cancels it: nothing more is queued; the runs already started finish and are recorded. */
    public Map<String, Object> cancel(Long backfillId, Long by, boolean api) {
        return this.change(backfillId, api, b -> {
            if (!this.store.move(b.id, OPEN, "CANCELLED", by)) {
                throw conflict("backfill-closed", "This backfill is " + words(b.status) + " already.");
            }
            this.store.cancelPending(b.id);
        });
    }

    /** Its failed periods are run again, each as a new run; a finished backfill opens again (within the workspace's cap). */
    public Map<String, Object> retryFailed(Long backfillId, boolean api) {
        return this.change(backfillId, api, b -> {
            if (!Arrays.asList("RUNNING", "PAUSED", "FAILED").contains(b.status)) {
                throw conflict("backfill-closed", "Only a running, paused or failed backfill's failed periods can be retried; this one is "
                    + words(b.status) + ".");
            }
            if (b.counts.failed == 0) {
                throw conflict("nothing-to-retry", "This backfill has no failed period to retry.");
            }
            if ("FAILED".equals(b.status)) {
                this.store.lockWorkspace(b.tenantId);
                int open = this.store.openCount(b.tenantId);
                if (open >= this.settings.maxOpen(b.tenantId)) {
                    throw conflict("too-many-backfills", String.format("This workspace has %d open backfills, the most it may have at once; "
                        + "retrying opens this one again. Wait for one to finish, or cancel one.", open));
                }
            }
            int reopened = this.store.reopenFailed(b.id, b.jobId);
            if (reopened == 0) {
                throw conflict("backfill-overlaps", "Every failed period of this backfill is in another open backfill of this schedule now.");
            }
            if ("FAILED".equals(b.status)) {
                this.store.move(b.id, Collections.singletonList("FAILED"), "RUNNING", null);
            }
        });
    }

    private interface Change {
        void apply(BackfillStore.Backfill locked);
    }

    private Map<String, Object> change(Long backfillId, boolean api, Change change) {
        BackfillStore.Backfill seen = this.visible(backfillId, api);
        this.tx.execute(status -> {
            BackfillStore.Backfill locked = this.store.lock(seen.id, false).orElseThrow(() -> notFound("No such backfill."));
            change.apply(locked);
            return null;
        });
        return this.describe(this.store.find(seen.id).orElseThrow(() -> notFound("No such backfill.")), !api && readsCosts());
    }

    // ------------------------------------------------------------------------------------------------------- reading

    public Map<String, Object> list(Long jobId, List<String> statuses, Integer page, Integer limit) {
        requireWorkspace();
        int size = limit == null ? 25 : Math.max(1, Math.min(100, limit));
        int number = page == null ? 1 : Math.max(1, page);
        List<String> wanted = new ArrayList<>();
        for (String s : statuses == null ? Collections.<String>emptyList() : statuses) {
            String word = s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
            if (!STATUSES.contains(word)) {
                throw invalid("A backfill's status is one of " + String.join(", ", STATUSES) + ".");
            }
            wanted.add(word);
        }
        Map<String, Object> found = this.store.list(jobId, wanted, JobOwnership.sqlPredicate("j"), number, size);
        List<Map<String, Object>> rows = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<BackfillStore.Backfill> backfills = (List<BackfillStore.Backfill>) found.get("rows");
        for (BackfillStore.Backfill b : backfills) {
            rows.add(this.describe(b, false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", found.get("total"));
        out.put("page", number);
        out.put("limit", size);
        out.put("maxOpen", this.settings.maxOpen(TenantContext.getTenantId()));
        out.put("maxConcurrency", this.settings.maxConcurrency());
        return out;
    }

    /** One backfill, a page of its periods with their runs, and (administrators) what its runs were billed so far. */
    public Map<String, Object> detail(Long backfillId, String slotState, Integer page, boolean api) {
        return this.detail(backfillId, slotState, page, api, null, null);
    }

    /**
     * As detail(), the periods in a column's order (owner 2026-10-27, "add a sort on each table"): BackfillStore.SLOT_SORTS --
     * date, state, run, tries -- asc or desc; none is the periods' own order, oldest first. Pages stay {@value #SLOTS_PAGE}.
     */
    public Map<String, Object> detail(Long backfillId, String slotState, Integer page, boolean api, String sort, String dir) {
        ListSort.Choice order;
        try {
            order = ListSort.parse(sort, dir, BackfillStore.SLOT_SORTS.keySet());
        } catch (ListSort.Refused unknown) {
            throw invalid(unknown.getMessage());
        }
        BackfillStore.Backfill b = this.visible(backfillId, api);
        Map<String, Object> out = this.describe(b, !api && readsCosts());
        String state = slotState == null || slotState.trim().isEmpty() ? null : slotState.trim().toUpperCase(Locale.ROOT);
        if (state != null && !Arrays.asList("PENDING", "QUEUED", "COMPLETED", "FAILED", "SKIPPED", "CANCELLED").contains(state)) {
            throw invalid("A period's state is one of PENDING, QUEUED, COMPLETED, FAILED, SKIPPED, CANCELLED.");
        }
        int number = page == null ? 1 : Math.max(1, page);
        List<Map<String, Object>> periods = new ArrayList<>();
        for (BackfillStore.Slot s : this.store.slots(b.id, state, SLOTS_PAGE, (number - 1) * SLOTS_PAGE, order)) {
            periods.add(slot(s));
        }
        out.put("periods", periods);
        out.put("periodsPage", number);
        out.put("periodsLimit", SLOTS_PAGE);
        if (order != null) {
            out.put("periodsSort", order.column);
            out.put("periodsDir", order.dirWord());
        }
        if (!api && readsCosts()) {
            out.put("billed", this.billed(b));
        }
        return out;
    }

    /** The backfill a run is part of, for the run's page; empty for any other run (or one the caller does not see). */
    public Optional<Map<String, Object>> ofRun(Long jobQueueId) {
        Long tenant = TenantContext.getTenantId();
        if (jobQueueId == null || jobQueueId <= 0 || (!TenantContext.isPlatformAdmin() && (tenant == null || tenant <= 0))) {
            return Optional.empty();
        }
        Optional<Map<String, Object>> found = this.store.ofRun(jobQueueId);
        if (found.isPresent()) {
            try {
                this.visibleJob((Long) found.get().get("jobId"), false);
            } catch (Refused notTheirs) {
                return Optional.empty();
            }
        }
        return found;
    }

    /** A backfill as both the console and the API read it. */
    Map<String, Object> describe(BackfillStore.Backfill b, boolean costs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("backfillId", b.id);
        out.put("jobId", b.jobId);
        out.put("jobName", b.jobName);
        out.put("status", b.status);
        out.put("step", b.step);
        out.put("from", b.from == null ? null : b.from.toString());
        out.put("to", b.to == null ? null : b.to.toString());
        out.put("zone", b.zone);
        out.put("concurrency", b.concurrency);
        out.put("rerunCompleted", b.rerunCompleted);
        out.put("parameters", RunParameters.read(b.params).params);
        out.put("total", b.total);
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("pending", b.counts.pending);
        counts.put("queued", b.counts.queued);
        counts.put("completed", b.counts.completed);
        counts.put("failed", b.counts.failed);
        counts.put("skipped", b.counts.skipped);
        counts.put("cancelled", b.counts.cancelled);
        out.put("counts", counts);
        out.put("done", b.counts.done());
        out.put("holdReason", b.holdReason);
        out.put("createdBy", b.createdBy);
        out.put("createdVia", b.createdVia);
        out.put("dateCreated", b.dateCreated == null ? null : b.dateCreated.toString());
        out.put("endedAt", b.endedAt == null ? null : b.endedAt.toString());
        out.put("pausedAt", b.pausedAt == null ? null : b.pausedAt.toString());
        out.put("eta", this.eta(b));
        if (costs) {
            Map<String, Object> estimate = new LinkedHashMap<>();
            estimate.put("perRun", b.estimatePerRun);
            estimate.put("total", b.estimateTotal);
            estimate.put("currency", b.estimateCurrency);
            estimate.put(b.estimatePerRun == null ? "unavailable" : "basis", b.estimateBasis);
            out.put("estimate", estimate);
        }
        return out;
    }

    static Map<String, Object> slot(BackfillStore.Slot s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", s.date == null ? null : s.date.toString());
        out.put("start", s.start == null ? null : s.start.toString());
        out.put("end", s.end == null ? null : s.end.toString());
        out.put("state", s.state);
        out.put("jobQueueId", s.jobQueueId);
        out.put("tries", s.tries);
        out.put("note", s.note);
        out.put("runStatus", s.runStatus);
        out.put("runStartedAt", s.runStartedAt == null ? null : s.runStartedAt.toString());
        out.put("runEndedAt", s.runEndedAt == null ? null : s.runEndedAt.toString());
        return out;
    }

    /**
     * When the rest should be done: the periods left over the concurrency, each wave taking the backfill's own completed runs'
     * average time (else the schedule's last 20 runs'). Null while nothing is known, or nothing is left, or it is paused.
     */
    Map<String, Object> eta(BackfillStore.Backfill b) {
        int left = b.counts.pending + b.counts.queued;
        if (left == 0 || !"RUNNING".equals(b.status)) {
            return null;
        }
        Double average = this.store.averageRunSeconds(b.id, b.jobId);
        if (average == null) {
            return null;
        }
        long waves = (left + b.concurrency - 1) / b.concurrency;
        // Each wave also waits for the dispatcher's next pass and the run's hand-off.
        long seconds = Math.round(waves * (average + BackfillDispatcher.TICK_SECONDS));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seconds", seconds);
        out.put("at", this.clock.instant().plusSeconds(seconds).toString());
        out.put("basis", String.format("%d left, %d at a time, about %d s a run", left, b.concurrency, Math.round(average)));
        return out;
    }

    // -------------------------------------------------------------------------------------------------------- costs

    /**
     * The pipeline's average run cost (billing's per-run shares, UX review B9) x the runs: the schedule's own finished runs of
     * the last 30 days, else every schedule's of its pipeline. Never throws: no billed run, or billing not answering, is said.
     */
    Estimate estimate(BackfillStore.Job job, int runs) {
        Estimate estimate = new Estimate();
        try {
            ZoneId zone = this.zones.zoneOf(job.tenantId);
            LocalDate today = LocalDate.ofInstant(this.clock.instant(), zone);
            for (boolean byPipeline : new boolean[] {false, true}) {
                List<Long> ids = this.store.recentFinishedRuns(job, byPipeline, 100);
                if (ids.isEmpty()) {
                    continue;
                }
                BillingPort.RunCosts costs = this.billing.runCosts(job.tenantId, today.minusDays(30), today, ids);
                if (costs.costs.isEmpty()) {
                    continue;
                }
                BigDecimal sum = BigDecimal.ZERO;
                for (BigDecimal c : costs.costs.values()) {
                    sum = sum.add(c);
                }
                estimate.perRun = sum.divide(BigDecimal.valueOf(costs.costs.size()), 5, RoundingMode.HALF_UP);
                estimate.total = estimate.perRun.multiply(BigDecimal.valueOf(runs)).setScale(5, RoundingMode.HALF_UP);
                estimate.currency = costs.currency;
                estimate.basis = String.format("The average of %d billed run(s) of this %s in the last 30 days x %d run(s).", costs.costs.size(),
                    byPipeline ? "pipeline" : "schedule", runs);
                return estimate;
            }
            estimate.unavailable = "No run of this pipeline was billed in the last 30 days, so there is nothing to estimate from. Its runs are "
                + "billed as any run.";
        } catch (BillingPort.BillingUnavailable unavailable) {
            logger.warn("No cost estimate for job {}: {}", job.jobId, unavailable.getMessage());
            estimate.unavailable = "Billing could not be asked just now, so there is no estimate. The runs are billed as any run.";
        }
        return estimate;
    }

    /** What the backfill's runs were billed so far: {total, runs, currency}, or why it cannot be said. */
    Map<String, Object> billed(BackfillStore.Backfill b) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Long> runs = this.store.runsOf(b.id);
        out.put("runs", runs.size());
        if (runs.isEmpty()) {
            out.put("total", BigDecimal.ZERO);
            out.put("billedRuns", 0);
            return out;
        }
        try {
            ZoneId zone = this.zones.zoneOf(b.tenantId);
            LocalDate from = b.dateCreated == null ? LocalDate.ofInstant(this.clock.instant(), zone) : LocalDate.ofInstant(b.dateCreated, zone);
            LocalDate to = LocalDate.ofInstant(this.clock.instant(), zone);
            BillingPort.RunCosts costs = this.billing.runCosts(b.tenantId, from, to, runs.size() > 500 ? runs.subList(0, 500) : runs);
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal c : costs.costs.values()) {
                sum = sum.add(c);
            }
            out.put("total", sum);
            out.put("billedRuns", costs.costs.size());
            out.put("currency", costs.currency);
        } catch (BillingPort.BillingUnavailable unavailable) {
            out.put("unavailable", "Billing could not be asked just now.");
        }
        return out;
    }

    static String words(String status) {
        return status == null ? "gone" : status.toLowerCase(Locale.ROOT);
    }

    static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
