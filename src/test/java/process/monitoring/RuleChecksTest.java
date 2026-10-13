package process.monitoring;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-355: each kind's rule on its own -- what it finds, what it leaves out (another workspace's, another pipeline's, outside
 * its window), and when a finding stops (which is what resolves an alert).
 */
class RuleChecksTest {

    static final Instant NOW = Instant.parse("2026-10-13T15:00:00Z");
    static final long A = 2960L;
    static final long B = 2961L;

    static AlertRule rule(AlertKind kind, Long tenant, int threshold, int window) {
        AlertRule rule = new AlertRule();
        rule.id = 1L;
        rule.kind = kind;
        rule.tenantId = tenant;
        rule.name = kind.label();
        rule.threshold = threshold;
        rule.windowMinutes = window;
        return rule;
    }

    static WorkspaceSignals.Failure failure(long tenant, long job, long run, int minutesAgo) {
        WorkspaceSignals.Failure f = new WorkspaceSignals.Failure();
        f.tenantId = tenant;
        f.jobId = job;
        f.runId = run;
        f.status = "Failed";
        f.endedAt = NOW.minus(Duration.ofMinutes(minutesAgo));
        f.jobName = "Claims intake (synthetic)";
        f.message = "Step 2 <pick> failed: The input has no column no_such_column.";
        return f;
    }

    @Test
    void oneFailedRunIsAnAlertForItsPipelineOnly() {
        List<Finding> found = RuleChecks.runFailed(rule(AlertKind.RUN_FAILED, A, 1, 60),
            Arrays.asList(failure(A, 10, 100, 5), failure(B, 20, 200, 5)), Collections.emptyMap(), NOW);
        assertThat(found).extracting(f -> f.subject).containsExactly("pipeline:10");
        assertThat(found.get(0).title).isEqualTo("Claims intake (synthetic) failed");
        assertThat(found.get(0).detail).contains("Run 100 failed").contains("no_such_column");
        assertThat(found.get(0).link).isEqualTo("/runs/failed?pipeline=10");
        assertThat(found.get(0).runId).isEqualTo(100L);
    }

    @Test
    void nFailuresInXMinutesCountsOnlyTheWindowAndOnlyAtTheThreshold() {
        AlertRule burst = rule(AlertKind.RUN_FAILED, A, 3, 10);
        List<WorkspaceSignals.Failure> two = Arrays.asList(failure(A, 10, 101, 2), failure(A, 10, 102, 4), failure(A, 10, 103, 30));
        assertThat(RuleChecks.runFailed(burst, two, Collections.emptyMap(), NOW)).isEmpty();
        List<WorkspaceSignals.Failure> three = new ArrayList<>(two);
        three.add(failure(A, 10, 104, 9));
        List<Finding> found = RuleChecks.runFailed(burst, three, Collections.emptyMap(), NOW);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).title).isEqualTo("3 runs of Claims intake (synthetic) failed in 10 minutes");
        assertThat(found.get(0).runId).isEqualTo(101L);
    }

    @Test
    void aLaterCompletedRunClearsIt() {
        AlertRule one = rule(AlertKind.RUN_FAILED, A, 1, 60);
        Map<Long, Instant> completed = new HashMap<>();
        completed.put(10L, NOW.minus(Duration.ofMinutes(1)));
        assertThat(RuleChecks.runFailed(one, Collections.singletonList(failure(A, 10, 100, 5)), completed, NOW)).isEmpty();
        completed.put(10L, NOW.minus(Duration.ofMinutes(9)));
        assertThat(RuleChecks.runFailed(one, Collections.singletonList(failure(A, 10, 100, 5)), completed, NOW)).hasSize(1);
    }

    @Test
    void aRuleForOnePipelineIgnoresTheOthers() {
        AlertRule one = rule(AlertKind.RUN_FAILED, A, 1, 60);
        one.jobId = 11L;
        assertThat(RuleChecks.runFailed(one, Collections.singletonList(failure(A, 10, 100, 5)), Collections.emptyMap(), NOW)).isEmpty();
    }

    @Test
    void aSkippedOrOverdueSlotIsAMissedSchedule() {
        WorkspaceSignals.Skip skip = new WorkspaceSignals.Skip();
        skip.tenantId = A;
        skip.jobId = 10;
        skip.runId = 300;
        skip.status = "Skip";
        skip.at = NOW.minus(Duration.ofMinutes(3));
        skip.jobName = "Every minute (synthetic)";
        WorkspaceSignals.Overdue late = new WorkspaceSignals.Overdue();
        late.tenantId = A;
        late.jobId = 12;
        late.due = NOW.minus(Duration.ofMinutes(20));
        late.jobName = "Nightly (synthetic)";
        WorkspaceSignals.Overdue other = new WorkspaceSignals.Overdue();
        other.tenantId = B;
        other.jobId = 13;
        other.due = NOW.minus(Duration.ofMinutes(20));
        List<Finding> found = RuleChecks.scheduleMissed(rule(AlertKind.SCHEDULE_MISSED, A, 1, 60), Collections.singletonList(skip),
            Arrays.asList(late, other), NOW);
        assertThat(found).extracting(f -> f.subject).containsExactlyInAnyOrder("pipeline:10", "pipeline:12");
        Finding skipped = found.stream().filter(f -> f.subject.equals("pipeline:10")).findFirst().get();
        assertThat(skipped.title).isEqualTo("Every minute (synthetic) missed a scheduled run in an hour");
        assertThat(skipped.detail).contains("previous run was still going");
        Finding overdue = found.stream().filter(f -> f.subject.equals("pipeline:12")).findFirst().get();
        assertThat(overdue.title).isEqualTo("Nightly (synthetic)'s scheduled run has not started");
        assertThat(RuleChecks.scheduleMissed(rule(AlertKind.SCHEDULE_MISSED, A, 1, 2), Collections.singletonList(skip),
            Collections.emptyList(), NOW)).isEmpty();
    }

    static WorkspaceSignals.Timed timed(long tenant, long job, long run, int startedMinutesAgo, Integer endedMinutesAgo) {
        WorkspaceSignals.Timed t = new WorkspaceSignals.Timed();
        t.tenantId = tenant;
        t.jobId = job;
        t.runId = run;
        t.started = NOW.minus(Duration.ofMinutes(startedMinutesAgo));
        t.ended = endedMinutesAgo == null ? null : NOW.minus(Duration.ofMinutes(endedMinutesAgo));
        t.jobName = "Patient 360 (synthetic)";
        return t;
    }

    @Test
    void aRunPastFactorTimesItsP95IsSlowAndAShortOneNeverIs() {
        AlertRule slow = rule(AlertKind.RUN_SLOW, A, 1, 60);
        slow.factor = BigDecimal.valueOf(2);
        Map<Long, WorkspaceSignals.Usual> usual = new HashMap<>();
        usual.put(10L, new WorkspaceSignals.Usual(120, 12));
        usual.put(11L, new WorkspaceSignals.Usual(10, 30));
        List<Finding> found = RuleChecks.runSlow(slow, Arrays.asList(
            timed(A, 10, 500, 5, null),
            timed(A, 10, 501, 3, null),
            timed(A, 11, 502, 1, 0),
            timed(A, 10, 503, 200, 190),
            timed(B, 10, 504, 50, null)), usual, NOW);
        assertThat(found).extracting(f -> f.subject).containsExactly("run:500");
        assertThat(found.get(0).title).isEqualTo("Run 500 of Patient 360 (synthetic) is slow: 5 min so far");
        assertThat(found.get(0).detail).contains("2 min").contains("12 completed runs").contains("2.0x");
        Map<Long, WorkspaceSignals.Usual> noUsual = new HashMap<>();
        assertThat(RuleChecks.runSlow(slow, Collections.singletonList(timed(A, 10, 500, 500, null)), noUsual, NOW)).isEmpty();
    }

    @Test
    void aReviewOrApprovalWaitingPastTheWindowIsFound() {
        WorkspaceSignals.Waiting review = new WorkspaceSignals.Waiting();
        review.tenantId = A;
        review.jobId = 10;
        review.runId = 600;
        review.what = "review";
        review.since = NOW.minus(Duration.ofHours(5));
        review.jobName = "Claims (synthetic)";
        WorkspaceSignals.Waiting approval = new WorkspaceSignals.Waiting();
        approval.tenantId = A;
        approval.jobId = 10;
        approval.runId = 601;
        approval.what = "approval";
        approval.approvalId = 77L;
        approval.title = "Check the totals";
        approval.since = NOW.minus(Duration.ofMinutes(30));
        List<Finding> found = RuleChecks.reviewWaiting(rule(AlertKind.REVIEW_WAITING, A, 1, 240), Arrays.asList(review, approval), NOW);
        assertThat(found).extracting(f -> f.subject).containsExactly("review:600");
        assertThat(found.get(0).title).isEqualTo("Run 600 of Claims (synthetic) has waited 5 h for review");
        assertThat(RuleChecks.reviewWaiting(rule(AlertKind.REVIEW_WAITING, A, 1, 20), Arrays.asList(review, approval), NOW))
            .extracting(f -> f.subject).containsExactly("review:600", "approval:77");
    }

    static RuleChecks.Sample sample(String service, int minutesAgo, String status, Integer used, Integer max, Double afterGc, Instant started) {
        RuleChecks.Sample s = new RuleChecks.Sample();
        s.service = service;
        s.at = NOW.minus(Duration.ofMinutes(minutesAgo));
        s.status = status;
        s.heapUsedMb = used;
        s.heapMaxMb = max;
        s.heapAfterGcPct = afterGc;
        s.startedAt = started;
        return s;
    }

    @Test
    void aServiceIsUnhealthyOnlyAfterThresholdMinutesInARow() {
        Map<String, List<RuleChecks.Sample>> recent = new LinkedHashMap<>();
        recent.put("transcriber", Arrays.asList(sample("transcriber", 0, "UNREACHABLE", null, null, null, null),
            sample("transcriber", 1, "UNREACHABLE", null, null, null, null), sample("transcriber", 2, "UP", 100, 1000, 10.0, null)));
        recent.put("media", Arrays.asList(sample("media", 0, "DOWN", null, null, null, null), sample("media", 1, "UP", null, null, null, null)));
        List<Finding> found = RuleChecks.serviceUnhealthy(rule(AlertKind.SERVICE_UNHEALTHY, null, 2, 60), recent);
        assertThat(found).extracting(f -> f.subject).containsExactly("service:transcriber");
        assertThat(found.get(0).title).isEqualTo("transcriber is not answering (2 minutes in a row)");
        assertThat(RuleChecks.serviceUnhealthy(rule(AlertKind.SERVICE_UNHEALTHY, null, 3, 60), recent)).isEmpty();
    }

    @Test
    void aRestartIsFoundWhileItsStartIsInTheWindow() {
        Map<String, List<RuleChecks.Sample>> recent = new LinkedHashMap<>();
        Instant fresh = NOW.minus(Duration.ofMinutes(4));
        Instant old = NOW.minus(Duration.ofDays(2));
        recent.put("transcriber", Arrays.asList(sample("transcriber", 0, "UP", 1, 2, null, fresh), sample("transcriber", 5, "UP", 1, 2, null, old)));
        recent.put("ai", Collections.singletonList(sample("ai", 0, "UP", 1, 2, null, old)));
        List<Finding> found = RuleChecks.serviceRestarted(rule(AlertKind.SERVICE_RESTARTED, null, 1, 30), recent, NOW);
        assertThat(found).extracting(f -> f.subject).containsExactly("service:transcriber");
        assertThat(found.get(0).title).isEqualTo("transcriber restarted 4 min ago");
        assertThat(RuleChecks.serviceRestarted(rule(AlertKind.SERVICE_RESTARTED, null, 1, 3), recent, NOW)).isEmpty();
    }

    @Test
    void memoryHighOpensAtTheMarkAndClearsFivePointsUnder() {
        AlertRule memory = rule(AlertKind.MEMORY_HIGH, null, 85, 10);
        Map<String, List<RuleChecks.Sample>> recent = new LinkedHashMap<>();
        recent.put("media", Collections.singletonList(sample("media", 0, "UP", 3000, 4000, 82.0, null)));
        assertThat(RuleChecks.memoryHigh(memory, recent, new HashSet<>())).isEmpty();
        assertThat(RuleChecks.memoryHigh(memory, recent, new HashSet<>(Collections.singletonList("service:media")))).hasSize(1);
        recent.put("media", Collections.singletonList(sample("media", 0, "UP", 3000, 4000, 79.0, null)));
        assertThat(RuleChecks.memoryHigh(memory, recent, new HashSet<>(Collections.singletonList("service:media")))).isEmpty();
        recent.put("media", Collections.singletonList(sample("media", 0, "UP", 3900, 4000, null, null)));
        List<Finding> full = RuleChecks.memoryHigh(memory, recent, new HashSet<>());
        assertThat(full).hasSize(1);
        assertThat(full.get(0).title).isEqualTo("media memory is high: 98% of its heap in use");
    }

    @Test
    void probesBecomeKafkaDiskAndBillingAlerts() {
        Map<String, RuleChecks.Probe> probes = new LinkedHashMap<>();
        probes.put("kafka-lag:notifications", probe("kafka-lag:notifications", true, 1500.0, "1500 messages behind"));
        probes.put("kafka-lag:workflow", probe("kafka-lag:workflow", true, 3.0, "3 messages behind"));
        probes.put("disk:/", probe("disk:/", true, 7.5, "7.5 GB free"));
        probes.put("billing-reconcile", probe("billing-reconcile", false, 1.0, "workspace 2960 (daily_mismatch)"));
        assertThat(RuleChecks.kafkaLag(rule(AlertKind.KAFKA_LAG, null, 1000, 10), probes)).extracting(f -> f.subject)
            .containsExactly("kafka:notifications");
        assertThat(RuleChecks.diskLow(rule(AlertKind.DISK_LOW, null, 10, 10), probes)).extracting(f -> f.title)
            .containsExactly("Disk space low: 7.5% free");
        assertThat(RuleChecks.diskLow(rule(AlertKind.DISK_LOW, null, 5, 10), probes)).isEmpty();
        assertThat(RuleChecks.billingMismatch(probes)).extracting(f -> f.detail).containsExactly("workspace 2960 (daily_mismatch)");
        probes.put("billing-reconcile", probe("billing-reconcile", true, 0.0, "agrees"));
        assertThat(RuleChecks.billingMismatch(probes)).isEmpty();
    }

    static RuleChecks.Probe probe(String name, boolean ok, Double reading, String detail) {
        RuleChecks.Probe p = new RuleChecks.Probe();
        p.name = name;
        p.at = NOW;
        p.ok = ok;
        p.reading = reading;
        p.detail = detail;
        return p;
    }

    @Test
    void durationsReadAsPeopleSayThem() {
        assertThat(RuleChecks.human(Duration.ofSeconds(45))).isEqualTo("45 s");
        assertThat(RuleChecks.human(Duration.ofMinutes(12))).isEqualTo("12 min");
        assertThat(RuleChecks.human(Duration.ofMinutes(150))).isEqualTo("2 h 30 min");
        assertThat(RuleChecks.human(Duration.ofDays(3))).isEqualTo("3 days");
        assertThat(RuleChecks.minutes(60)).isEqualTo("an hour");
        assertThat(RuleChecks.minutes(1440)).isEqualTo("a day");
        assertThat(RuleChecks.minutes(15)).isEqualTo("15 minutes");
    }
}
