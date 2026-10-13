package process.monitoring;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import process.retention.DataClass;

/**
 * MIG-355: what one rule finds, from what the pass read -- pure functions, no database, so each kind's rule is tested on
 * its own (RuleChecksTest). A finding's subject is its de-duplication key; a subject a rule no longer finds is resolved.
 */
public final class RuleChecks {

    /** A run counts as slow only past this, whatever its usual: a 5-second pipeline taking 12 is not news. */
    static final Duration SLOW_AT_LEAST = Duration.ofMinutes(1);
    /** Memory high clears this many points under the threshold, so a heap hovering at it does not open and close every minute. */
    static final int MEMORY_HYSTERESIS = 5;
    /** The heap in use at this share of its maximum is high whatever the after-GC figure says. */
    static final double HEAP_FULL = 0.95;

    private RuleChecks() {
    }

    // ------------------------------------------------------------------------------------------------------ workspace

    /**
     * RUN_FAILED: threshold or more failed runs of a pipeline in the window, unless a later run of it completed. MIG-347: a
     * backfill's failures are their own group, one alert per backfill (subject backfill:&lt;id&gt;) however many of its dates
     * fail, linked to the backfill; a later run of the schedule does not clear them -- a failed date stays failed until the
     * backfill retries it (WorkspaceSignals reads only dates still failed).
     */
    public static List<Finding> runFailed(AlertRule rule, List<WorkspaceSignals.Failure> failures, Map<Long, Instant> lastCompleted,
        Instant now) {
        Instant since = now.minus(Duration.ofMinutes(rule.windowMinutes));
        Map<String, List<WorkspaceSignals.Failure>> byGroup = new LinkedHashMap<>();
        for (WorkspaceSignals.Failure failure : failures) {
            if (failure.tenantId != rule.tenantId || (rule.jobId != null && failure.jobId != rule.jobId)
                || failure.endedAt == null || failure.endedAt.isBefore(since)) {
                continue;
            }
            String group = failure.backfillId == null ? "pipeline:" + failure.jobId : "backfill:" + failure.backfillId;
            byGroup.computeIfAbsent(group, id -> new ArrayList<>()).add(failure);
        }
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, List<WorkspaceSignals.Failure>> entry : byGroup.entrySet()) {
            List<WorkspaceSignals.Failure> runs = entry.getValue();
            if (runs.size() < rule.threshold) {
                continue;
            }
            WorkspaceSignals.Failure latest = runs.get(0);
            for (WorkspaceSignals.Failure run : runs) {
                if (run.endedAt.isAfter(latest.endedAt)) {
                    latest = run;
                }
            }
            String name = name(latest.jobName, latest.jobId);
            String detail = "Run " + latest.runId + ("Interrupt".equals(latest.status) ? " stopped responding and was closed" : " failed")
                + (latest.message == null || latest.message.trim().isEmpty() ? "." : ": " + oneLine(latest.message, 400));
            if (latest.backfillId != null) {
                String title = runs.size() == 1 ? "A date of backfill #" + latest.backfillId + " of " + name + " failed"
                    : runs.size() + " dates of backfill #" + latest.backfillId + " of " + name + " failed";
                out.add(new Finding(entry.getKey(), title, detail, "/runs/backfills?backfill=" + latest.backfillId, latest.jobId, latest.runId,
                    null));
                continue;
            }
            Instant recovered = lastCompleted.get(latest.jobId);
            if (recovered != null && recovered.isAfter(latest.endedAt)) {
                continue;
            }
            String title = rule.threshold <= 1 && runs.size() == 1 ? name + " failed"
                : runs.size() + " runs of " + name + " failed in " + minutes(rule.windowMinutes);
            out.add(new Finding(entry.getKey(), title, detail, "/runs/failed?pipeline=" + latest.jobId, latest.jobId, latest.runId, null));
        }
        return out;
    }

    /** SCHEDULE_MISSED: a slot skipped or missed in the window, or a slot overdue now. */
    public static List<Finding> scheduleMissed(AlertRule rule, List<WorkspaceSignals.Skip> skips, List<WorkspaceSignals.Overdue> overdue,
        Instant now) {
        Instant since = now.minus(Duration.ofMinutes(rule.windowMinutes));
        Map<Long, List<WorkspaceSignals.Skip>> byJob = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        for (WorkspaceSignals.Skip skip : skips) {
            if (skip.tenantId != rule.tenantId || (rule.jobId != null && skip.jobId != rule.jobId) || skip.at == null || skip.at.isBefore(since)) {
                continue;
            }
            byJob.computeIfAbsent(skip.jobId, id -> new ArrayList<>()).add(skip);
            names.put(skip.jobId, skip.jobName);
        }
        Map<Long, WorkspaceSignals.Overdue> late = new LinkedHashMap<>();
        for (WorkspaceSignals.Overdue o : overdue) {
            if (o.tenantId == rule.tenantId && (rule.jobId == null || o.jobId == rule.jobId)) {
                late.put(o.jobId, o);
                names.put(o.jobId, o.jobName);
            }
        }
        List<Finding> out = new ArrayList<>();
        for (Long job : names.keySet()) {
            List<WorkspaceSignals.Skip> missed = byJob.getOrDefault(job, Collections.emptyList());
            WorkspaceSignals.Overdue due = late.get(job);
            String name = name(names.get(job), job);
            String title;
            StringBuilder detail = new StringBuilder();
            if (due != null) {
                title = name + "'s scheduled run has not started";
                detail.append("Its slot was due ").append(ago(due.due, now)).append(" ago and no run was made.");
            } else {
                title = name + " missed " + (missed.size() == 1 ? "a scheduled run" : missed.size() + " scheduled runs") + " in "
                    + minutes(rule.windowMinutes);
            }
            if (!missed.isEmpty()) {
                long skipped = missed.stream().filter(s -> "Skip".equals(s.status)).count();
                long down = missed.size() - skipped;
                if (detail.length() > 0) {
                    detail.append(' ');
                }
                if (skipped > 0) {
                    detail.append(skipped).append(skipped == 1 ? " slot was" : " slots were").append(" skipped because the previous run was still going.");
                }
                if (down > 0) {
                    detail.append(skipped > 0 ? " " : "").append(down).append(down == 1 ? " slot was" : " slots were")
                        .append(" missed while the platform was down.");
                }
            }
            Long lastRun = missed.isEmpty() ? null : missed.get(0).runId;
            out.add(new Finding("pipeline:" + job, title, detail.toString(), "/pipelines/schedules/" + job + "/executions", job, lastRun, null));
        }
        return out;
    }

    /** RUN_SLOW: a run going, or ended in the window, past factor x its pipeline's p95 (and past a minute). */
    public static List<Finding> runSlow(AlertRule rule, List<WorkspaceSignals.Timed> runs, Map<Long, WorkspaceSignals.Usual> usual, Instant now) {
        Instant since = now.minus(Duration.ofMinutes(rule.windowMinutes));
        double factor = rule.factorOr(2);
        List<Finding> out = new ArrayList<>();
        for (WorkspaceSignals.Timed run : runs) {
            if (run.tenantId != rule.tenantId || (rule.jobId != null && run.jobId != rule.jobId) || run.started == null) {
                continue;
            }
            if (run.ended != null && run.ended.isBefore(since)) {
                continue;
            }
            WorkspaceSignals.Usual normal = usual.get(run.jobId);
            if (normal == null || normal.p95Seconds <= 0) {
                continue;
            }
            Duration took = Duration.between(run.started, run.ended == null ? now : run.ended);
            if (took.compareTo(SLOW_AT_LEAST) <= 0 || took.getSeconds() <= factor * normal.p95Seconds) {
                continue;
            }
            String name = name(run.jobName, run.jobId);
            String title = run.ended == null
                ? "Run " + run.runId + " of " + name + " is slow: " + human(took) + " so far"
                : "Run " + run.runId + " of " + name + " was slow: " + human(took);
            String detail = String.format(Locale.ROOT, "Its usual is %s (p95 of %d completed runs over 30 days); this rule calls %.1fx that slow.",
                human(Duration.ofSeconds(Math.round(normal.p95Seconds))), normal.runs, factor);
            out.add(new Finding("run:" + run.runId, title, detail, "/pipelines/schedules/" + run.jobId + "/runs/" + run.runId, run.jobId, run.runId,
                null));
        }
        return out;
    }

    /** REVIEW_WAITING: a review or an Approval step's request waiting longer than the window. */
    public static List<Finding> reviewWaiting(AlertRule rule, List<WorkspaceSignals.Waiting> waiting, Instant now) {
        Instant before = now.minus(Duration.ofMinutes(rule.windowMinutes));
        List<Finding> out = new ArrayList<>();
        for (WorkspaceSignals.Waiting w : waiting) {
            if (w.tenantId != rule.tenantId || (rule.jobId != null && w.jobId != rule.jobId) || w.since == null || !w.since.isBefore(before)) {
                continue;
            }
            String name = name(w.jobName, w.jobId);
            boolean approval = "approval".equals(w.what);
            String subject = approval ? "approval:" + w.approvalId : "review:" + w.runId;
            String title = approval
                ? "\"" + oneLine(w.title == null ? "Approval" : w.title, 80) + "\" (run " + w.runId + " of " + name + ") has waited " + ago(w.since, now)
                : "Run " + w.runId + " of " + name + " has waited " + ago(w.since, now) + " for review";
            String detail = approval ? "The run is paused at its Approval step until a reviewer decides in the Review inbox."
                : "Its results are held until a reviewer approves or rejects them.";
            out.add(new Finding(subject, title, detail, "/pipelines/schedules/" + w.jobId + "/runs/" + w.runId, w.jobId, w.runId, null));
        }
        return out;
    }

    /**
     * RETENTION (MIG-354): a retention class whose latest result paused (over the share a pass removes without asking) or
     * failed, and a last pass that failed with no class to name. One alert per class ("retention:RUN_LOGS"); it clears
     * when a later pass of the class ends otherwise.
     */
    public static List<Finding> retention(AlertRule rule, List<WorkspaceSignals.RetentionTrouble> trouble) {
        List<Finding> out = new ArrayList<>();
        boolean classes = false;
        for (WorkspaceSignals.RetentionTrouble t : trouble) {
            if (t.tenantId == rule.tenantId && t.dataClass != null) {
                classes = true;
            }
        }
        for (WorkspaceSignals.RetentionTrouble t : trouble) {
            if (t.tenantId != rule.tenantId || (t.dataClass == null && classes)) {
                continue;
            }
            String what = t.dataClass == null ? "The retention pass" : "Retention of " + label(t.dataClass);
            boolean paused = "PAUSED".equals(t.state);
            String title = paused ? what + " is waiting for an administrator's confirmation" : what + " failed (pass " + t.passId + ")";
            String detail = t.note == null || t.note.trim().isEmpty() ? (paused ? "Nothing was removed." : "The next pass tries again.")
                : oneLine(t.note, 400);
            out.add(new Finding("retention:" + (t.dataClass == null ? "pass" : t.dataClass), title, detail,
                "/administration/data-policies#retention", null, null, null));
        }
        return out;
    }

    private static String label(String dataClass) {
        return DataClass.of(dataClass).map(c -> c.label().toLowerCase(Locale.ROOT)).orElse(dataClass);
    }

    // ------------------------------------------------------------------------------------------------------ platform

    /** One minute of one service, as the evaluator reads it back. */
    public static final class Sample {
        public String service;
        public Instant at;
        public String status;
        public Integer heapUsedMb;
        public Integer heapMaxMb;
        public Double heapAfterGcPct;
        public Instant startedAt;
    }

    /** A platform probe's latest reading. */
    public static final class Probe {
        public String name;
        public Instant at;
        public boolean ok;
        public Double reading;
        public String detail;
    }

    /** SERVICE_UNHEALTHY: the last threshold samples of a service all not UP. {@code recent} newest first per service. */
    public static List<Finding> serviceUnhealthy(AlertRule rule, Map<String, List<Sample>> recent) {
        return serviceUnhealthy(rule, recent, Collections.emptyMap());
    }

    /**
     * SERVICE_UNHEALTHY, with the planned deploys: a sample taken inside one of the service's deploy windows is the deploy
     * (Platform › Reliability marks it), so only the samples outside them count -- the last threshold of those all not UP.
     */
    public static List<Finding> serviceUnhealthy(AlertRule rule, Map<String, List<Sample>> recent, Map<String, List<DeployWindow>> planned) {
        List<Finding> out = new ArrayList<>();
        int needed = Math.max(1, rule.threshold);
        for (Map.Entry<String, List<Sample>> entry : recent.entrySet()) {
            List<DeployWindow> windows = planned.get(entry.getKey());
            List<Sample> samples = new ArrayList<>();
            for (Sample sample : entry.getValue()) {
                if (!DeployWindow.anyCovers(windows, sample.at)) {
                    samples.add(sample);
                }
            }
            if (samples.size() < needed) {
                continue;
            }
            boolean down = true;
            for (int i = 0; i < needed; i++) {
                if ("UP".equals(samples.get(i).status)) {
                    down = false;
                    break;
                }
            }
            if (down) {
                String status = samples.get(0).status;
                out.add(Finding.of("service:" + entry.getKey(), entry.getKey() + " is " + word(status) + " (" + needed
                    + (needed == 1 ? " minute)" : " minutes in a row)"), "Its health check answered " + status + " at each of the last "
                    + needed + " checks.", "/platform/reliability?service=" + entry.getKey()));
            }
        }
        return out;
    }

    /** SERVICE_RESTARTED: a service whose JVM started within the window (its latest sample's start time). */
    public static List<Finding> serviceRestarted(AlertRule rule, Map<String, List<Sample>> recent, Instant now) {
        return serviceRestarted(rule, recent, now, Collections.emptyMap());
    }

    /**
     * SERVICE_RESTARTED, with the planned deploys: a JVM start inside one of the service's deploy windows is the deploy, not
     * news. A start outside every window -- a crash, an out-of-memory kill, a docker restart by hand, or a deploy whose
     * window expired before the service came back -- alerts, as before.
     */
    public static List<Finding> serviceRestarted(AlertRule rule, Map<String, List<Sample>> recent, Instant now,
        Map<String, List<DeployWindow>> planned) {
        Instant since = now.minus(Duration.ofMinutes(rule.windowMinutes));
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, List<Sample>> entry : recent.entrySet()) {
            List<DeployWindow> windows = planned.get(entry.getKey());
            TreeSet<Instant> starts = new TreeSet<>();
            for (Sample sample : entry.getValue()) {
                if (sample.startedAt != null && !sample.startedAt.isBefore(since) && !DeployWindow.anyCovers(windows, sample.startedAt)) {
                    starts.add(sample.startedAt);
                }
            }
            if (starts.isEmpty()) {
                continue;
            }
            Instant latest = starts.last();
            out.add(Finding.of("service:" + entry.getKey(), entry.getKey() + " restarted " + ago(latest, now) + " ago",
                (starts.size() > 1 ? starts.size() + " starts in the last " + minutes(rule.windowMinutes) + ". " : "")
                    + "Not a planned deploy (scripts/deploy.sh announces those, and they do not alert): a crash, an out-of-memory kill "
                    + "or a restart by hand.",
                "/platform/reliability?service=" + entry.getKey()));
        }
        return out;
    }

    /**
     * MEMORY_HIGH: the latest heap after GC at or over threshold percent, or the heap in use at 95% of its maximum. An
     * alert already open stays open until it is {@value #MEMORY_HYSTERESIS} points under the threshold.
     */
    public static List<Finding> memoryHigh(AlertRule rule, Map<String, List<Sample>> recent, Set<String> openSubjects) {
        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, List<Sample>> entry : recent.entrySet()) {
            Sample latest = entry.getValue().isEmpty() ? null : entry.getValue().get(0);
            if (latest == null || !"UP".equals(latest.status)) {
                continue;
            }
            String subject = "service:" + entry.getKey();
            double bar = openSubjects.contains(subject) ? rule.threshold - MEMORY_HYSTERESIS : rule.threshold;
            Double afterGc = latest.heapAfterGcPct;
            Double inUse = latest.heapUsedMb != null && latest.heapMaxMb != null && latest.heapMaxMb > 0
                ? 100.0 * latest.heapUsedMb / latest.heapMaxMb : null;
            boolean high = (afterGc != null && afterGc >= bar) || (inUse != null && inUse >= HEAP_FULL * 100);
            if (!high) {
                continue;
            }
            String figure = afterGc != null ? String.format(Locale.ROOT, "%.0f%% of its heap after garbage collection", afterGc)
                : String.format(Locale.ROOT, "%.0f%% of its heap in use", inUse);
            out.add(Finding.of(subject, entry.getKey() + " memory is high: " + figure,
                String.format(Locale.ROOT, "Heap %s of %s MB in use; the rule's high-water mark is %d%%.",
                    latest.heapUsedMb == null ? "?" : String.valueOf(latest.heapUsedMb), latest.heapMaxMb == null ? "?" : String.valueOf(latest.heapMaxMb),
                    rule.threshold), "/platform/reliability?service=" + entry.getKey()));
        }
        return out;
    }

    /** KAFKA_LAG: each consumer group threshold or more messages behind (probes kafka-lag:&lt;group&gt;). */
    public static List<Finding> kafkaLag(AlertRule rule, Map<String, Probe> probes) {
        List<Finding> out = new ArrayList<>();
        for (Probe probe : probes.values()) {
            if (!probe.name.startsWith(HealthCollector.KAFKA_PREFIX) || probe.reading == null || probe.reading < rule.threshold) {
                continue;
            }
            String group = probe.name.substring(HealthCollector.KAFKA_PREFIX.length());
            out.add(Finding.of("kafka:" + group, "Kafka consumer group " + group + " is " + Math.round(probe.reading) + " messages behind",
                probe.detail, "/platform/reliability"));
        }
        return out;
    }

    /** DISK_LOW: a disk probe under threshold percent free. */
    public static List<Finding> diskLow(AlertRule rule, Map<String, Probe> probes) {
        List<Finding> out = new ArrayList<>();
        for (Probe probe : probes.values()) {
            if (!probe.name.startsWith(HealthCollector.DISK_PREFIX) || probe.reading == null || probe.reading >= rule.threshold) {
                continue;
            }
            out.add(Finding.of("disk:" + probe.name.substring(HealthCollector.DISK_PREFIX.length()),
                String.format(Locale.ROOT, "Disk space low: %.1f%% free", probe.reading), probe.detail, "/platform/reliability"));
        }
        return out;
    }

    /** BILLING_MISMATCH: the billing reconciliation probe says the month does not agree. */
    public static List<Finding> billingMismatch(Map<String, Probe> probes) {
        Probe probe = probes.get(HealthCollector.BILLING_PROBE);
        if (probe == null || probe.ok) {
            return Collections.emptyList();
        }
        return Collections.singletonList(Finding.of("billing:reconcile", "Billing reconciliation does not agree", probe.detail,
            "/platform/reliability"));
    }

    // ------------------------------------------------------------------------------------------------------ words

    static String name(String jobName, long jobId) {
        return jobName == null || jobName.trim().isEmpty() ? "pipeline " + jobId : oneLine(jobName, 120);
    }

    static String oneLine(String text, int max) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    static String minutes(int minutes) {
        if (minutes % 1440 == 0) {
            return minutes == 1440 ? "a day" : (minutes / 1440) + " days";
        }
        if (minutes % 60 == 0) {
            return minutes == 60 ? "an hour" : (minutes / 60) + " hours";
        }
        return minutes == 1 ? "a minute" : minutes + " minutes";
    }

    static String ago(Instant at, Instant now) {
        return human(Duration.between(at, now));
    }

    static String human(Duration d) {
        long s = Math.max(0, d.getSeconds());
        if (s < 90) {
            return s + " s";
        }
        long m = Math.round(s / 60.0);
        if (m < 90) {
            return m + " min";
        }
        long h = s / 3600;
        long rest = (s % 3600) / 60;
        if (h < 48) {
            return rest == 0 ? h + " h" : h + " h " + rest + " min";
        }
        return (h / 24) + " days";
    }

    private static String word(String status) {
        if (status == null) {
            return "unknown";
        }
        switch (status) {
            case "UNREACHABLE": return "not answering";
            case "DOWN": return "down";
            case "OUT_OF_SERVICE": return "out of service";
            default: return status.toLowerCase(Locale.ROOT);
        }
    }
}
