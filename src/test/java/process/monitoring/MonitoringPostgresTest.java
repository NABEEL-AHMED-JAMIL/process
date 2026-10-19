package process.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.barco.notifications.contract.MailRequested;
import org.barco.notifications.contract.NotificationCreated;
import org.barco.platform.tenancy.TenantScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import process.ScratchPostgres;
import process.identity.IdentityPort;
import process.notifications.NotificationPort;
import process.security.TenantContext;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import java.util.function.Supplier;

/**
 * MIG-355 end to end on etl_job as the changelog builds it, through the application's pool (process_app, row security):
 * the evaluator over two workspaces' rules and failed runs, and the platform's.
 *
 * <ul>
 *   <li>Tenant isolation: workspace A's alert is A's row, told only to A's people; B's to B's. A caller of A reads none of B's
 *   rules or alerts, and no workspace reads the platform's.</li>
 *   <li>Role scoping: a rule naming TENANT_ADMIN and a group tells the admin and that group's member, not another member.</li>
 *   <li>De-duplication: the same failure next minute touches the alert -- no second notice, mail or webhook.</li>
 *   <li>Auto-resolve: a later completed run of the pipeline resolves it, with a notice and alert.resolved.</li>
 *   <li>Quiet hours: mail held while they last and sent after; the bell is not held.</li>
 *   <li>Runs › Failed reads only the caller's workspace (the platform administrator's: every workspace's).</li>
 * </ul>
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL.
 */
class MonitoringPostgresTest {

    static final long A = 8861L;
    static final long B = 8862L;
    static final long JOB_A = 886101L;
    static final long JOB_B = 886201L;
    static final long ADMIN_A = 9861L;
    static final long REVIEWER_A = 9862L;
    static final long VIEWER_A = 9863L;
    static final long ADMIN_B = 9871L;
    static final long PLATFORM = 9800L;
    static final long REVIEWERS = 77L;
    static final long VIEWERS = 78L;

    static ScratchPostgres db;
    static JdbcTemplate login;
    static JdbcTemplate app;
    static long nextRun = 88600001L;

    final IdentityPort identity = mock(IdentityPort.class);
    final NotificationPort notifications = mock(NotificationPort.class);
    final HealthCollector health = mock(HealthCollector.class);
    final List<Object[]> notices = new ArrayList<>();
    final List<Object[]> mails = new ArrayList<>();
    AlertRules rules;
    AlertIncidents incidents;
    AlertEngine engine;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("monitoring_engine");
        login = db.jdbc();
        app = db.appJdbc();
        for (long t : new long[]{A, B}) {
            login.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", t, "t" + t, "T" + t);
        }
        login.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (?, now(), 'Manual', 'Claims intake A (synthetic)', 'Active', 1, ?)", JOB_A, A);
        login.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (?, now(), 'Manual', 'Ledger B (synthetic)', 'Active', 1, ?)", JOB_B, B);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    static IdentityPort.Person person(long id, Long tenant, String role, Long profile) {
        return new IdentityPort.Person(id, tenant, "user" + id + "@example.test", "User " + id, role, "Active", null, null, profile);
    }

    @BeforeEach
    void wire() {
        login.update("DELETE FROM api_event_out WHERE kind = 'alert'");
        login.update("DELETE FROM alert_rule WHERE tenant_id IS NOT NULL");
        login.update("DELETE FROM alert_incident");
        login.update("UPDATE alert_rule SET enabled = false WHERE tenant_id IS NULL");
        login.update("DELETE FROM job_queue WHERE job_id IN (?, ?)", JOB_A, JOB_B);
        List<IdentityPort.Person> peopleA = Arrays.asList(person(ADMIN_A, A, "TENANT_ADMIN", null), person(REVIEWER_A, A, "TENANT_USER", REVIEWERS),
            person(VIEWER_A, A, "TENANT_USER", VIEWERS));
        List<IdentityPort.Person> peopleB = Collections.singletonList(person(ADMIN_B, B, "TENANT_ADMIN", null));
        List<IdentityPort.Person> everyone = new ArrayList<>(peopleA);
        everyone.addAll(peopleB);
        everyone.add(person(PLATFORM, null, "PLATFORM_ADMIN", null));
        when(this.identity.members(any())).thenAnswer(call -> {
            TenantScope scope = call.getArgument(0);
            if (scope.isAllTenants()) {
                return everyone;
            }
            return scope.admits(A) ? peopleA : scope.admits(B) ? peopleB : Collections.emptyList();
        });
        when(this.identity.workspaces(anyCollection())).thenReturn(Arrays.asList(new IdentityPort.Workspace(A, "Workspace A", "a", "Active"),
            new IdentityPort.Workspace(B, "Workspace B", "b", "Active")));
        doAnswer(call -> this.notices.add(new Object[]{call.getArgument(0), call.getArgument(1)})).when(this.notifications)
            .notificationCreated(any(), any());
        doAnswer(call -> {
            this.mails.add(new Object[]{call.getArgument(0), call.getArgument(1)});
            return "Mail queued.";
        }).when(this.notifications).mailRequested(any(), any(), any());
        this.rules = new AlertRules(app);
        this.incidents = new AlertIncidents(app);
        AlertDelivery delivery = new AlertDelivery(this.notifications, this.identity, app, "http://localhost:4400");
        this.engine = new AlertEngine(this.rules, this.incidents, new WorkspaceSignals(app), this.health, delivery, app,
            new DataSourceTransactionManager(db.appPool()));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    static long run(long job, String status, Instant ended, String message) {
        long id = nextRun++;
        login.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, start_time, end_time, status, job_send, attempt, "
            + "job_status_message, end_reason) VALUES (?, ?, ?, ?, ?, ?, 'Active', true, 1, ?, ?)", id, job, status,
            Timestamp.from(ended.minusSeconds(60)), Timestamp.from(ended.minusSeconds(30)), Timestamp.from(ended), message,
            "Completed".equals(status) ? null : "WORKER");
        return id;
    }

    AlertRule ruleOf(long tenant, long admin, AlertKind kind, int threshold, boolean email, boolean webhook, Long... groups) {
        TenantContext.set(tenant, "TENANT_ADMIN", admin, "admin@" + tenant);
        try {
            AlertRule rule = new AlertRule();
            rule.kind = kind;
            rule.threshold = threshold;
            rule.windowMinutes = 60;
            rule.email = email;
            rule.webhook = webhook;
            rule.roles = new LinkedHashSet<>(Collections.singletonList("TENANT_ADMIN"));
            rule.groups = new LinkedHashSet<>(Arrays.asList(groups));
            return this.rules.save(rule, tenant, admin);
        } finally {
            TenantContext.clear();
        }
    }

    List<Long> told(Long tenantArg) {
        return this.notices.stream().filter(n -> Objects.equals(n[0], tenantArg))
            .map(n -> ((NotificationCreated) n[1]).getAppUserId()).collect(Collectors.toList());
    }

    @Test
    void eachWorkspacesAlertIsItsOwnAndToldOnlyToItsOwnPeopleByRoleAndGroup() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ruleOf(A, ADMIN_A, AlertKind.RUN_FAILED, 1, true, true, REVIEWERS);
        ruleOf(B, ADMIN_B, AlertKind.RUN_FAILED, 1, false, false);
        long failedA = run(JOB_A, "Failed", now.minusSeconds(120), "Step 1 <intake> failed: (line 1) invalid char (synthetic).");
        run(JOB_B, "Failed", now.minusSeconds(60), "Step 1 <x> failed: boom (synthetic).");

        AlertEngine.Pass pass = this.engine.evaluate(now);

        assertThat(pass.opened).isEqualTo(2);
        Map<Long, Long> tenantOfAlert = new HashMap<>();
        login.query("SELECT alert_incident_id, tenant_id FROM alert_incident", rs -> {
            tenantOfAlert.put(rs.getLong(1), rs.getLong(2));
        });
        assertThat(tenantOfAlert.values()).containsExactlyInAnyOrder(A, B);
        assertThat(told(A)).containsExactlyInAnyOrder(ADMIN_A, REVIEWER_A);
        assertThat(told(B)).containsExactly(ADMIN_B);
        for (Object[] notice : this.notices) {
            NotificationCreated n = (NotificationCreated) notice[1];
            assertThat(n.getType()).isEqualTo("ALERT");
            assertThat(n.getTitle()).isEqualTo(A == (Long) notice[0] ? "Claims intake A (synthetic) failed" : "Ledger B (synthetic) failed");
        }
        assertThat(this.mails).extracting(m -> ((MailRequested) m[1]).getRecipient())
            .containsExactlyInAnyOrder("user" + ADMIN_A + "@example.test", "user" + REVIEWER_A + "@example.test");
        MailRequested mail = (MailRequested) this.mails.get(0)[1];
        assertThat(mail.getTemplate()).isEqualTo(MailRequested.Template.ALERT);
        assertThat(String.valueOf(mail.getBodyMap().get("detail"))).contains("Run " + failedA).contains("(line 1)")
            .doesNotContain("<intake>");
        assertThat(String.valueOf(mail.getBodyMap().get("link"))).isEqualTo("http://localhost:4400/runs/failed?pipeline=" + JOB_A);
        assertThat(login.queryForList("SELECT tenant_id FROM api_event_out WHERE kind = 'alert' AND alert_event = 'opened'", Long.class))
            .containsExactly(A);

        TenantContext.set(A, "TENANT_ADMIN", ADMIN_A, "a");
        assertThat(this.incidents.list(A, null, 50)).extracting(i -> i.tenantId).containsOnly(A).hasSize(1);
        assertThat(this.incidents.list(B, null, 50)).isEmpty();
        assertThat(this.rules.list(B)).isEmpty();
        assertThat(this.rules.list(null)).isEmpty();
        assertThat(this.incidents.list(null, null, 50)).isEmpty();
        assertThat(this.engine.resolveByHand(tenantOfAlert.entrySet().stream().filter(e -> e.getValue() == B).findFirst().get().getKey(), A,
            ADMIN_A, now)).isFalse();
    }

    @Test
    void theSameFailureNextMinuteIsOneAlertAndALaterCompletedRunResolvesIt() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ruleOf(A, ADMIN_A, AlertKind.RUN_FAILED, 1, false, true);
        run(JOB_A, "Failed", now.minusSeconds(120), "Step 2 <pick> failed: The input has no column no_such_column.");
        this.engine.evaluate(now);
        assertThat(told(A)).containsExactly(ADMIN_A);

        AlertEngine.Pass second = this.engine.evaluate(now.plusSeconds(60));
        assertThat(second.opened).isZero();
        assertThat(second.touched).isEqualTo(1);
        assertThat(told(A)).hasSize(1);
        assertThat(login.queryForObject("SELECT occurrences FROM alert_incident", Integer.class)).isEqualTo(2);
        assertThat(login.queryForObject("SELECT count(*) FROM api_event_out WHERE kind = 'alert'", Integer.class)).isEqualTo(1);

        run(JOB_A, "Completed", now.plusSeconds(90), "done");
        AlertEngine.Pass third = this.engine.evaluate(now.plusSeconds(120));
        assertThat(third.resolved).isEqualTo(1);
        assertThat(login.queryForMap("SELECT state, resolved_how FROM alert_incident")).containsEntry("state", "RESOLVED")
            .containsEntry("resolved_how", "AUTO");
        assertThat(((NotificationCreated) this.notices.get(this.notices.size() - 1)[1]).getTitle()).startsWith("Resolved: ");
        assertThat(login.queryForList("SELECT alert_event FROM api_event_out WHERE kind = 'alert' ORDER BY out_id", String.class))
            .containsExactly("opened", "resolved");
    }

    @Test
    void aMutedRuleKeepsItsAlertButTellsNobodyUntilTheMuteEnds() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ruleOf(A, ADMIN_A, AlertKind.RUN_FAILED, 1, false, true);
        long ruleId = login.queryForObject("SELECT alert_rule_id FROM alert_rule WHERE tenant_id = ?", Long.class, A);
        assertThat(as(A, ADMIN_A, () -> this.rules.mute(ruleId, A, 30, ADMIN_A))).isTrue();
        assertThat(as(B, ADMIN_B, () -> this.rules.mute(ruleId, B, 30, ADMIN_B))).as("another workspace's rule").isFalse();
        run(JOB_A, "Failed", now.minusSeconds(60), "Step 1 <x> failed: boom (synthetic).");

        AlertEngine.Pass muted = this.engine.evaluate(now);

        assertThat(muted.opened).isEqualTo(1);
        assertThat(this.notices).isEmpty();
        assertThat(login.queryForObject("SELECT count(*) FROM api_event_out WHERE kind = 'alert'", Integer.class)).isZero();
        assertThat(as(A, ADMIN_A, () -> this.rules.firing(A)).get(ruleId)[1]).isEqualTo(1L);
        assertThat(as(B, ADMIN_B, () -> this.rules.firing(B))).isEmpty();

        assertThat(as(A, ADMIN_A, () -> this.rules.mute(ruleId, A, 0, ADMIN_A))).isTrue();
        this.engine.evaluate(now.plusSeconds(60));
        assertThat(told(A)).containsExactly(ADMIN_A);
        assertThat(login.queryForObject("SELECT count(*) FROM api_event_out WHERE kind = 'alert'", Integer.class)).isEqualTo(1);
        Assertions.assertThatThrownBy(() -> this.rules.mute(ruleId, A, AlertRules.MAX_MUTE_MINUTES + 1, ADMIN_A))
            .isInstanceOf(AlertRules.Refused.class);
    }

    static <T> T as(long tenant, long admin, Supplier<T> work) {
        TenantContext.set(tenant, "TENANT_ADMIN", admin, "admin@" + tenant);
        try {
            return work.get();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void nFailuresInTheWindowAndNotBefore() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ruleOf(A, ADMIN_A, AlertKind.RUN_FAILED, 3, false, false);
        run(JOB_A, "Failed", now.minusSeconds(300), "x");
        run(JOB_A, "Failed", now.minusSeconds(200), "x");
        assertThat(this.engine.evaluate(now).opened).isZero();
        run(JOB_A, "Failed", now.minusSeconds(100), "x");
        assertThat(this.engine.evaluate(now).opened).isEqualTo(1);
        assertThat(login.queryForObject("SELECT title FROM alert_incident", String.class))
            .isEqualTo("3 runs of Claims intake A (synthetic) failed in an hour");
    }

    @Test
    void quietHoursHoldTheMailNotTheBellAndSendItAfter() {
        Instant now = Instant.parse("2026-10-14T03:30:00Z");
        TenantContext.set(A, "TENANT_ADMIN", ADMIN_A, "a");
        AlertRule rule = new AlertRule();
        rule.kind = AlertKind.RUN_FAILED;
        rule.threshold = 1;
        rule.windowMinutes = 1440;
        rule.email = true;
        rule.roles = new LinkedHashSet<>(Collections.singletonList("TENANT_ADMIN"));
        rule.quietStart = LocalTime.of(22, 0);
        rule.quietEnd = LocalTime.of(7, 0);
        rule.quietZone = "America/Chicago";
        this.rules.save(rule, A, ADMIN_A);
        TenantContext.clear();
        run(JOB_A, "Failed", now.minusSeconds(60), "x");
        this.engine.evaluate(now);
        assertThat(told(A)).containsExactly(ADMIN_A);
        assertThat(this.mails).isEmpty();
        assertThat(login.queryForObject("SELECT email_held FROM alert_incident", Boolean.class)).isTrue();

        this.engine.evaluate(Instant.parse("2026-10-14T12:01:00Z"));
        assertThat(this.mails).hasSize(1);
        assertThat(login.queryForMap("SELECT email_held, emailed_at IS NOT NULL AS sent FROM alert_incident")).containsEntry("email_held", false)
            .containsEntry("sent", true);
        this.engine.evaluate(Instant.parse("2026-10-14T12:02:00Z"));
        assertThat(this.mails).hasSize(1);
    }

    @Test
    void thePlatformsRulesAreThePlatformAdministratorsAlone() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        login.update("UPDATE alert_rule SET enabled = true WHERE tenant_id IS NULL AND kind = 'DISK_LOW'");
        Map<String, RuleChecks.Probe> probes = new LinkedHashMap<>();
        RuleChecks.Probe disk = new RuleChecks.Probe();
        disk.name = "disk:/";
        disk.at = now;
        disk.ok = true;
        disk.reading = 4.2;
        disk.detail = "4 GB free (synthetic)";
        probes.put(disk.name, disk);
        when(this.health.probes()).thenReturn(probes);
        when(this.health.recent(any())).thenReturn(new HashMap<>());
        when(this.health.newestSample()).thenReturn(Optional.of(now));

        this.engine.evaluate(now);

        assertThat(login.queryForObject("SELECT count(*) FROM alert_incident WHERE tenant_id IS NULL AND kind = 'DISK_LOW' AND state = 'OPEN'",
            Integer.class)).isEqualTo(1);
        assertThat(this.notices).extracting(n -> ((NotificationCreated) n[1]).getAppUserId()).containsExactly(PLATFORM);
        TenantContext.set(A, "TENANT_ADMIN", ADMIN_A, "a");
        assertThat(this.incidents.list(null, null, 50)).isEmpty();
        TenantContext.set(null, "PLATFORM_ADMIN", PLATFORM, "p");
        assertThat(this.incidents.list(null, null, 50)).extracting(i -> i.kind).containsExactly("DISK_LOW");
        assertThat(this.rules.list(null)).hasSize(6);

        disk.reading = 30.0;
        TenantContext.clear();
        this.engine.evaluate(now.plusSeconds(60));
        assertThat(login.queryForObject("SELECT state FROM alert_incident WHERE kind = 'DISK_LOW'", String.class)).isEqualTo("RESOLVED");
    }

    @Test
    void aRestartInsideAPlannedDeployOpensNoAlertAndOneOutsideDoesAndResolves() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        login.update("UPDATE alert_rule SET enabled = true WHERE tenant_id IS NULL AND kind = 'SERVICE_RESTARTED'");
        Instant started = now.minusSeconds(240);
        RuleChecks.Sample sample = new RuleChecks.Sample();
        sample.service = "transcriber";
        sample.at = now;
        sample.status = "UP";
        sample.startedAt = started;
        Map<String, List<RuleChecks.Sample>> recent = new HashMap<>();
        recent.put("transcriber", Collections.singletonList(sample));
        when(this.health.probes()).thenReturn(new HashMap<>());
        when(this.health.recent(any())).thenReturn(recent);
        when(this.health.newestSample()).thenReturn(Optional.of(now));
        AlertDelivery delivery = new AlertDelivery(this.notifications, this.identity, app, "http://localhost:4400");
        DeployWindow deploy = new DeployWindow("transcriber", started.minusSeconds(20), started.plusSeconds(880), started.plusSeconds(50), "ok");
        AlertEngine planned = new AlertEngine(this.rules, this.incidents, new WorkspaceSignals(app), this.health, delivery,
            PlannedDeploys.of(Collections.singletonList(deploy)), app, new DataSourceTransactionManager(db.appPool()));

        AlertEngine.Pass inside = planned.evaluate(now);

        assertThat(inside.opened).isZero();
        assertThat(login.queryForObject("SELECT count(*) FROM alert_incident WHERE kind = 'SERVICE_RESTARTED'", Integer.class)).isZero();
        assertThat(this.notices).isEmpty();

        // The same restart with no window (a docker restart by hand): an alert, told to the platform administrator.
        AlertEngine.Pass outside = this.engine.evaluate(now.plusSeconds(60));
        assertThat(outside.opened).isEqualTo(1);
        assertThat(login.queryForObject("SELECT title FROM alert_incident WHERE kind = 'SERVICE_RESTARTED' AND state = 'OPEN'", String.class))
            .isEqualTo("transcriber restarted 5 min ago");
        assertThat(this.notices).extracting(n -> ((NotificationCreated) n[1]).getAppUserId()).containsExactly(PLATFORM);

        // Past the rule's 30 minutes the start is no longer news: resolved by itself.
        sample.at = now.plusSeconds(31 * 60);
        when(this.health.newestSample()).thenReturn(Optional.of(sample.at));
        this.engine.evaluate(now.plusSeconds(31 * 60));
        assertThat(login.queryForObject("SELECT state || '/' || resolved_how FROM alert_incident WHERE kind = 'SERVICE_RESTARTED'", String.class))
            .isEqualTo("RESOLVED/AUTO");
    }

    @Test
    void aTurnedOffRulesAlertsResolve() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        AlertRule rule = ruleOf(A, ADMIN_A, AlertKind.RUN_FAILED, 1, false, false);
        run(JOB_A, "Failed", now.minusSeconds(60), "x");
        this.engine.evaluate(now);
        login.update("UPDATE alert_rule SET enabled = false WHERE alert_rule_id = ?", rule.id);
        assertThat(this.engine.evaluate(now.plusSeconds(60)).resolved).isEqualTo(1);
    }

    @Test
    void failedRunsAreTheCallersWorkspacesOrEveryWorkspaceForThePlatform() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        long a1 = run(JOB_A, "Failed", now.minusSeconds(300), "Step 3 <report> failed: The step's input has 1,000,000 rows x 8 columns; a step that holds its whole "
            + "input in memory takes at most 50,000 rows.");
        long a2 = run(JOB_A, "Failed", now.minusSeconds(200), "Step 3 <report> failed: The step's input has 100,000 rows x 40 columns; a step that holds its whole "
            + "input in memory takes at most 50,000 rows.");
        run(JOB_B, "Failed", now.minusSeconds(100), "Step 1 <x> failed: boom.");
        login.update("INSERT INTO step_execution (tenant_id, job_queue_id, attempt, step_index, task_code, status, error, step_key) "
            + "VALUES (?, ?, 1, 2, 'render_pdf', 'Failed', '{\"message\": \"The step''s input has 1,000,000 rows x 8 columns; a step that holds its whole input in memory "
            + "takes at most 50,000 rows.\", \"timedOut\": false}', "
            + "'report')", A, a1);
        // UX review 2026-10-13: a2 is one of backfill 77's runs -- listed, but retried by the backfill, not from here.
        login.update("UPDATE job_queue SET run_lane = 1, backfill_id = 77 WHERE job_queue_id = ?", a2);
        FailedRuns failed = new FailedRuns(app, this.identity);

        TenantContext.set(A, "TENANT_USER", REVIEWER_A, "r");
        assertThat(failed.search(new FailedRuns.Query(), false).get("total")).as("a tenant user reads only the runs of jobs naming them")
            .isEqualTo(0);
        TenantContext.set(A, "TENANT_ADMIN", ADMIN_A, "a");
        Map<String, Object> mine = failed.search(new FailedRuns.Query(), false);
        assertThat(mine.get("total")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) mine.get("groups");
        assertThat(groups).hasSize(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) mine.get("items");
        assertThat(items).extracting(i -> i.get("runId")).containsExactly(a2, a1);
        assertThat(items.get(1)).containsEntry("task", "render_pdf").containsEntry("stepKey", "report").containsEntry("kind", "too_large");
        assertThat(items.get(0)).containsEntry("backfillId", 77L).containsEntry("retryable", false);
        assertThat(items.get(1)).containsEntry("backfillId", null).containsEntry("retryable", true);

        TenantContext.set(null, "PLATFORM_ADMIN", PLATFORM, "p");
        Map<String, Object> all = failed.search(new FailedRuns.Query(), true);
        assertThat(all.get("total")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        Map<String, Object> facets = (Map<String, Object>) all.get("facets");
        assertThat((List<?>) facets.get("workspaces")).hasSize(2);
        FailedRuns.Query onlyB = new FailedRuns.Query();
        onlyB.tenantIds = Collections.singletonList(B);
        Map<String, Object> b = failed.search(onlyB, true);
        assertThat(b.get("total")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> bFacets = (Map<String, Object>) b.get("facets");
        assertThat((List<?>) bFacets.get("workspaces")).as("a facet leaves its own filter out").hasSize(2);
    }
}
