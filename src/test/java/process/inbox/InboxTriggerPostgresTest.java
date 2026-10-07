package process.inbox;

import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.AcrossTenantsProxy;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.directory.WorkspaceDirectory;
import process.engine.BulkAction;
import process.engine.ProducerBulkEngine;
import process.model.dto.ResponseDto;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.TaskReferenceRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.notifications.NotificationPort;
import process.security.TenantContext;
import process.util.OpenSearchAuditLogClient;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-239, the board's "event-trigger integration test", Core's half: Storage's InboxArrived (the committed fixture,
 * inbox-arrived-v1.json) through the real listener, the real enqueuer (ProducerBulkEngine, BulkAction,
 * TransactionServiceImpl, Hibernate) and a Postgres built by the real changelog, as process_app under row security with
 * nobody signed in -- the listener works as the event's workspace only (RowSecurity.forTenant).
 *
 * Each job with an enabled inbox trigger whose pattern takes the file gets its own run, queued once per arrival, with
 * the file on the run (input_bucket, input_key); a redelivered event starts nothing more. MIG-360: an arrival is never
 * dropped -- a job with a run in flight, or a paused workspace, makes it wait, and the job's next run takes it (as many
 * waiting files at once as the trigger's batch size); an inactive job records it as Skipped with the reason. Another
 * workspace's jobs are never reached. Opt-in on NOTIFICATIONS_TEST_DB_URL (see ScratchPostgres).
 */
class InboxTriggerPostgresTest {

    static final long A = 4597L;
    static final long B = 4598L;
    static final String KEY = "intake/2026/09/28/0b8f6a52-3f5e-4c1a-9d7e-5a4b3c2d1e0f-invoices_q3.csv";

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private JdbcTemplate sql;
    private WorkspaceDirectory workspaces;
    private InboxTriggerService service;
    private InboxArrivalListener listener;
    private InboxQueueSweep sweep;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("inbox_trigger");
        jpa = new ScratchJpa(db.appPool());
    }

    @AfterAll
    static void drop() throws Exception {
        if (jpa != null) {
            jpa.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void rows() {
        this.sql = db.jdbc();
        this.sql.update("DELETE FROM inbox_arrival");
        this.sql.update("DELETE FROM job_inbox_trigger");
        this.sql.update("DELETE FROM job_audit_logs");
        this.sql.update("DELETE FROM job_audit_logs WHERE job_queue_id IN (SELECT job_queue_id FROM job_queue WHERE job_id BETWEEN 9501 AND 9599)");
        this.sql.update("DELETE FROM job_queue WHERE job_id BETWEEN 9501 AND 9599");
        this.sql.update("DELETE FROM source_job WHERE job_id BETWEEN 9501 AND 9599");
        for (long tenant : new long[] {A, B}) {
            this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?) ON CONFLICT DO NOTHING",
                tenant, "INBOX" + tenant, "Inbox " + tenant);
        }
        TransactionServiceImpl store = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class),
            jpa.repository(SchedulerRepository.class), jpa.repository(JobQueueRepository.class),
            jpa.repository(TaskReferenceRepository.class), jpa.repository(JobAuditLogRepository.class),
            jpa.repository(SourceTaskRepository.class), mock(OpenSearchAuditLogClient.class));
        BulkAction bulkAction = new BulkAction(store, mock(NotificationPort.class));
        ProducerBulkEngine engine = AcrossTenantsProxy.of(new ProducerBulkEngine(bulkAction, store, mock(JobMail.class), null, null,
            jpa.transactionManager()));
        this.workspaces = mock(WorkspaceDirectory.class);
        when(this.workspaces.pauseOf(anyLong())).thenReturn(Optional.empty());
        engine.useWorkspaceDirectory(this.workspaces);
        this.service = new InboxTriggerService(new JdbcInboxTriggerStore(new JdbcTemplate(db.appPool())), engine, store,
            jpa.transactionManager());
        this.listener = new InboxArrivalListener(this.service);
        this.sweep = new InboxQueueSweep(db.appJdbc(), this.service);
    }

    @AfterEach
    void signOut() {
        TenantContext.clear();
    }

    private void job(long jobId, long tenant, String status) {
        this.sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority, created_by) "
            + "VALUES (?, ?, ?, 'Manual', ?, ?, 1, 61)", tenant, jobId, Timestamp.valueOf(LocalDateTime.now().minusDays(1)), "inbox-" + jobId,
            status);
        this.sql.update("UPDATE source_job SET complete_job = false, fail_job = false, skip_job = false WHERE job_id = ?", jobId);
    }

    private void trigger(long jobId, String pattern, boolean enabled) {
        this.sql.update("INSERT INTO job_inbox_trigger (job_id, tenant_id, enabled, file_pattern) "
            + "SELECT job_id, tenant_id, ?, ? FROM source_job WHERE job_id = ?", enabled, pattern, jobId);
    }

    private List<Map<String, Object>> runsOf(long jobId) {
        return this.sql.queryForList("SELECT job_queue_id, job_status, run_manual, input_bucket, input_key, tenant_id FROM job_queue "
            + "WHERE job_id = ? ORDER BY job_queue_id", jobId);
    }

    private List<Map<String, Object>> arrivalsOf(long jobId) {
        return this.sql.queryForList("SELECT arrival_id, tenant_id, outcome, reason, job_queue_id, bucket, storage_key, file_name, bytes "
            + "FROM inbox_arrival WHERE job_id = ? ORDER BY inbox_arrival_id", jobId);
    }

    private static String event() throws Exception {
        return InboxArrivalContractTest.fixture();
    }

    /** The same file arriving in workspace B instead. */
    private static String eventInB() throws Exception {
        return event().replace("4597", "4598");
    }

    @Test
    void anArrivalStartsEachMatchingJobOnceWithTheFileOnTheRun() throws Exception {
        job(9501, A, "Active");
        trigger(9501, "*.csv", true);
        job(9502, A, "Active");
        trigger(9502, null, true);
        job(9503, A, "Active");
        trigger(9503, "*.pdf", true);
        job(9504, A, "Active");
        trigger(9504, "*.csv", false);
        job(9505, A, "Active");
        job(9506, B, "Active");
        trigger(9506, "*", true);

        this.listener.onArrival(event());

        for (long started : new long[] {9501, 9502}) {
            List<Map<String, Object>> runs = runsOf(started);
            assertThat(runs).as("job %s", started).hasSize(1);
            assertThat(runs.get(0)).containsEntry("job_status", "Queue").containsEntry("run_manual", false)
                .containsEntry("input_bucket", "acme-inbox").containsEntry("input_key", KEY).containsEntry("tenant_id", A);
            List<Map<String, Object>> arrivals = arrivalsOf(started);
            assertThat(arrivals).hasSize(1);
            assertThat(arrivals.get(0)).containsEntry("arrival_id", "0b8f6a52-3f5e-4c1a-9d7e-5a4b3c2d1e0f").containsEntry("outcome", "Started")
                .containsEntry("job_queue_id", runs.get(0).get("job_queue_id")).containsEntry("bucket", "acme-inbox")
                .containsEntry("storage_key", KEY).containsEntry("file_name", "invoices_q3.csv").containsEntry("bytes", 2048L)
                .containsEntry("tenant_id", A);
            assertThat(this.sql.queryForObject("SELECT job_running_status FROM source_job WHERE job_id = ?", String.class, started))
                .isEqualTo("Queue");
            assertThat(this.sql.queryForList("SELECT log_detail FROM job_audit_logs WHERE job_queue_id = ?", String.class,
                runs.get(0).get("job_queue_id"))).anySatisfy(line -> assertThat(line).contains("invoices_q3.csv").contains("inbox"));
        }
        for (long untouched : new long[] {9503, 9504, 9505, 9506}) {
            assertThat(runsOf(untouched)).as("job %s", untouched).isEmpty();
            assertThat(arrivalsOf(untouched)).as("job %s", untouched).isEmpty();
        }
    }

    @Test
    void theSameArrivalDeliveredAgainStartsNothingMore() throws Exception {
        job(9511, A, "Active");
        trigger(9511, "*.csv", true);

        this.listener.onArrival(event());
        // The run finished, so the in-flight rule is not what holds the second delivery back: the arrival id is.
        this.sql.update("UPDATE job_queue SET job_status = 'Completed' WHERE job_id = 9511");
        this.sql.update("UPDATE source_job SET job_running_status = 'Completed' WHERE job_id = 9511");
        this.listener.onArrival(event());
        this.listener.onArrival(event());

        assertThat(runsOf(9511)).hasSize(1);
        assertThat(arrivalsOf(9511)).hasSize(1);
    }

    @Test
    void aJobWithARunInFlightKeepsTheArrivalWaitingAndItsNextRunTakesIt() throws Exception {
        job(9521, A, "Active");
        trigger(9521, null, true);
        this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status) "
            + "VALUES (952101, 9521, 'Start', now(), now(), 'Active')");
        this.sql.update("UPDATE source_job SET job_running_status = 'Start' WHERE job_id = 9521");

        this.listener.onArrival(event());
        this.listener.onArrival(event());

        assertThat(runsOf(9521)).extracting(r -> r.get("job_queue_id")).containsExactly(952101L);
        List<Map<String, Object>> arrivals = arrivalsOf(9521);
        assertThat(arrivals).hasSize(1);
        assertThat(arrivals.get(0)).containsEntry("outcome", "Waiting").containsEntry("job_queue_id", null);
        assertThat((String) arrivals.get(0).get("reason")).contains("in flight").contains("next run");
        // While the run is in flight the sweep leaves the file where it is.
        assertThat(this.sweep.sweep()).isZero();

        finish(952101L);
        assertThat(this.sweep.sweep()).isEqualTo(1);
        assertThat(this.sweep.sweep()).as("the next pass finds the new run in flight").isZero();

        List<Map<String, Object>> runs = runsOf(9521);
        assertThat(runs).hasSize(2);
        Map<String, Object> next = runs.stream().filter(r -> !Long.valueOf(952101L).equals(r.get("job_queue_id"))).findFirst().get();
        assertThat(next).containsEntry("job_status", "Queue").containsEntry("input_key", KEY).containsEntry("tenant_id", A);
        assertThat(arrivalsOf(9521)).hasSize(1).first().satisfies(a -> {
            assertThat(a).containsEntry("outcome", "Started").containsEntry("job_queue_id", next.get("job_queue_id"))
                .containsEntry("reason", null);
        });
        assertThat(this.sql.queryForObject("SELECT started_at IS NOT NULL FROM inbox_arrival WHERE job_id = 9521", Boolean.class)).isTrue();
    }

    /** Two starts racing: the one-in-flight index refuses the second run, and the arrival waits for the one that won. */
    @Test
    void whenTheIndexRefusesTheRunTheArrivalWaits() throws Exception {
        job(9531, A, "Active");
        trigger(9531, null, true);
        // A run the job's own status does not show yet: the pre-check passes, the index does not.
        this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, status) "
            + "VALUES (953101, 9531, 'Queue', now(), 'Active')");

        this.listener.onArrival(event());

        assertThat(runsOf(9531)).extracting(r -> r.get("job_queue_id")).containsExactly(953101L);
        List<Map<String, Object>> arrivals = arrivalsOf(9531);
        assertThat(arrivals).hasSize(1);
        assertThat(arrivals.get(0)).containsEntry("outcome", "Waiting");
    }

    @Test
    void anInactiveJobSkipsTheArrivalAndAPausedWorkspaceKeepsItWaiting() throws Exception {
        job(9541, A, "Inactive");
        trigger(9541, null, true);
        job(9542, B, "Active");
        trigger(9542, null, true);
        when(this.workspaces.pauseOf(B)).thenReturn(Optional.of(new WorkspaceDirectory.Pause(B, "Suspended", new Timestamp(0))));

        this.listener.onArrival(event());
        this.listener.onArrival(eventInB());

        assertThat(runsOf(9541)).isEmpty();
        assertThat(arrivalsOf(9541)).hasSize(1).first().satisfies(a -> {
            assertThat(a.get("outcome")).isEqualTo("Skipped");
            assertThat((String) a.get("reason")).contains("not active");
        });
        assertThat(runsOf(9542)).isEmpty();
        assertThat(arrivalsOf(9542)).hasSize(1).first().satisfies(a -> assertThat(a.get("outcome")).isEqualTo("Waiting"));
        assertThat(this.sweep.sweep()).as("paused: the file waits").isZero();

        // The workspace is active again: the file's run starts.
        when(this.workspaces.pauseOf(B)).thenReturn(Optional.empty());
        assertThat(this.sweep.sweep()).isEqualTo(1);
        assertThat(runsOf(9542)).hasSize(1);
        assertThat(arrivalsOf(9542)).first().satisfies(a -> assertThat(a.get("outcome")).isEqualTo("Started"));
    }

    @Test
    void filesThatWaitedForAJobSwitchedOffSinceAreSkippedSayingSo() throws Exception {
        job(9545, A, "Active");
        trigger(9545, null, true);
        busy(9545, 954501L);
        this.listener.onArrival(event());
        this.sql.update("UPDATE source_job SET job_status = 'Inactive' WHERE job_id = 9545");
        finish(954501L);

        assertThat(this.sweep.sweep()).isZero();

        assertThat(runsOf(9545)).hasSize(1);
        assertThat(arrivalsOf(9545)).first().satisfies(a -> {
            assertThat(a.get("outcome")).isEqualTo("Skipped");
            assertThat((String) a.get("reason")).contains("not active");
        });
    }

    // ---- MIG-360: none lost ---------------------------------------------------------------------------------------------

    /** The card's test: 40 images uploaded at once give 40 runs, one after the other, none lost and none twice. */
    @Test
    void fortyFilesAtOnceGiveFortyRunsInTurn() throws Exception {
        job(9581, A, "Active");
        trigger(9581, "*.jpeg", true);

        arriveAtOnce(40, 8);

        assertThat(runsOf(9581)).as("one run at a time").hasSize(1);
        assertThat(outcomes(9581)).containsEntry("Started", 1L).containsEntry("Waiting", 39L);
        TenantContext.set(A, "TENANT_ADMIN", 60L, "admin@acme.example");
        assertThat(this.service.trigger(9581L).getData()).hasFieldOrPropertyWithValue("waiting", 39);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shown = (List<Map<String, Object>>) this.service.arrivals(9581L, 200).getData();
        assertThat(shown).hasSize(40);
        assertThat(shown.stream().filter(a -> "Waiting".equals(a.get("outcome"))).map(a -> a.get("place")))
            .containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 39).boxed().collect(Collectors.toList()));
        TenantContext.clear();

        int runs = drain(9581);

        assertThat(runs).isEqualTo(39);
        List<Map<String, Object>> made = runsOf(9581);
        assertThat(made).hasSize(40);
        assertThat(outcomes(9581)).containsOnlyKeys("Started").containsEntry("Started", 40L);
        // Each file has its own run, and each run its own file: none lost, none twice.
        assertThat(this.sql.queryForObject("SELECT count(DISTINCT job_queue_id) FROM inbox_arrival WHERE job_id = 9581", Long.class)).isEqualTo(40);
        assertThat(made.stream().map(r -> r.get("input_key")).distinct().count()).isEqualTo(40);
        // In the order they arrived.
        assertThat(this.sql.queryForList("SELECT job_queue_id FROM inbox_arrival WHERE job_id = 9581 ORDER BY inbox_arrival_id", Long.class))
            .isSorted();
    }

    /** The trigger's batch size: the files that waited go ten at a time, the run naming each (keysOf). */
    @Test
    void aBatchSizeTakesTheWaitingFilesTenAtATime() throws Exception {
        job(9591, A, "Active");
        trigger(9591, null, true);
        this.sql.update("UPDATE job_inbox_trigger SET batch_size = 10 WHERE job_id = 9591");
        busy(9591, 959101L);

        arriveAtOnce(40, 8);
        assertThat(outcomes(9591)).containsEntry("Waiting", 40L);

        int runs = drain(9591);

        assertThat(runs).isEqualTo(4);
        assertThat(outcomes(9591)).containsOnlyKeys("Started").containsEntry("Started", 40L);
        JdbcInboxTriggerStore store = new JdbcInboxTriggerStore(db.appJdbc());
        List<Long> made = this.sql.queryForList("SELECT job_queue_id FROM job_queue WHERE job_id = 9591 AND job_queue_id <> 959101 "
            + "ORDER BY job_queue_id", Long.class);
        assertThat(made).hasSize(4);
        for (Long run : made) {
            List<String> keys = RowSecurity.forTenant(A, () -> store.keysOf(run));
            assertThat(keys).hasSize(10);
            String own = this.sql.queryForObject("SELECT input_key FROM job_queue WHERE job_queue_id = ?", String.class, run);
            assertThat(keys.get(0)).as("the run is named after its first file").isEqualTo(own);
        }
    }

    @Test
    void theBatchSizeIsSetWithTheTriggerAndHeldToOneToFifty() throws Exception {
        job(9596, A, "Active");
        TenantContext.set(A, "TENANT_ADMIN", 60L, "admin@acme.example");

        assertThat(this.service.save(9596L, true, null, 0).getStatus()).isEqualTo("ERROR");
        assertThat(this.service.save(9596L, true, null, 51).getStatus()).isEqualTo("ERROR");
        ResponseDto saved = this.service.save(9596L, true, "*.jpeg", 5);
        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
        assertThat(saved.getMessage()).contains("up to 5");
        assertThat(this.service.trigger(9596L).getData()).hasFieldOrPropertyWithValue("batchSize", 5);
        // Saved again without one: the trigger keeps its batch size.
        this.service.save(9596L, false, "*.jpeg");
        assertThat(this.service.trigger(9596L).getData()).hasFieldOrPropertyWithValue("batchSize", 5)
            .hasFieldOrPropertyWithValue("enabled", false);
    }

    private void busy(long jobId, long runId) {
        this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status) "
            + "VALUES (?, ?, 'Running', now(), now(), 'Active')", runId, jobId);
        this.sql.update("UPDATE source_job SET job_running_status = 'Running' WHERE job_id = ?", jobId);
    }

    private void finish(long runId) {
        this.sql.update("UPDATE job_queue SET job_status = 'Completed', end_time = now() WHERE job_queue_id = ?", runId);
        this.sql.update("UPDATE source_job SET job_running_status = 'Completed' WHERE job_id = (SELECT job_id FROM job_queue "
            + "WHERE job_queue_id = ?)", runId);
    }

    /** Finishes the job's run in flight and sweeps, until nothing more starts; answers how many runs the sweep made. */
    private int drain(long jobId) {
        int made = 0;
        for (int pass = 0; pass < 200; pass++) {
            for (Long inFlight : this.sql.queryForList("SELECT job_queue_id FROM job_queue WHERE job_id = ? AND job_status IN "
                + "('Queue', 'Start', 'Running')", Long.class, jobId)) {
                finish(inFlight);
            }
            int started = this.sweep.sweep();
            if (started == 0) {
                return made;
            }
            made += started;
        }
        return made;
    }

    /** {@code count} different files arriving at once, on {@code threads} threads (the listener's), all for workspace A. */
    private void arriveAtOnce(int count, int threads) throws Exception {
        String fixture = event();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> sent = new ArrayList<>();
            CountDownLatch go = new CountDownLatch(1);
            for (int i = 1; i <= count; i++) {
                String arrival = String.format("0b8f6a52-3f5e-4c1a-9d7e-%012d", i);
                String name = String.format("chest-xray-%03d.jpeg", i);
                String message = fixture.replace("0b8f6a52-3f5e-4c1a-9d7e-5a4b3c2d1e0f", arrival).replace("invoices_q3.csv", name);
                sent.add(pool.submit(() -> {
                    go.await();
                    this.listener.onArrival(message);
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> one : sent) {
                one.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private Map<String, Long> outcomes(long jobId) {
        Map<String, Long> out = new TreeMap<>();
        this.sql.query("SELECT outcome, count(*) AS n FROM inbox_arrival WHERE job_id = ? GROUP BY outcome", rs -> {
            out.put(rs.getString("outcome"), rs.getLong("n"));
        }, jobId);
        return out;
    }

    /** An arrival names its workspace; the listener works as that workspace alone, so B's arrival never reaches A's jobs. */
    @Test
    void anotherWorkspacesArrivalNeverStartsThisWorkspacesJobs() throws Exception {
        job(9551, A, "Active");
        trigger(9551, null, true);
        job(9552, B, "Active");
        trigger(9552, null, true);

        this.listener.onArrival(eventInB());

        assertThat(runsOf(9551)).isEmpty();
        assertThat(arrivalsOf(9551)).isEmpty();
        assertThat(runsOf(9552)).hasSize(1).first().satisfies(r -> assertThat(r.get("tenant_id")).isEqualTo(B));
    }

    /** An event Core cannot read is logged and skipped: no retry makes it readable. */
    @Test
    void anUnreadableEventIsSkipped() throws Exception {
        job(9561, A, "Active");
        trigger(9561, null, true);

        this.listener.onArrival("{\"eventType\":\"platform.storage.inbox-arrived.v1\"}");
        this.listener.onArrival("not json");

        assertThat(runsOf(9561)).isEmpty();
    }

    // ---- the console: sourceJob.json/inboxTrigger and inboxArrivals ---------------------------------------------------

    @Test
    void whoeverMaySeeAJobSetsItsTriggerAndReadsItsArrivals() throws Exception {
        job(9571, A, "Active");
        TenantContext.set(A, "TENANT_ADMIN", 60L, "admin@acme.example");

        ResponseDto saved = this.service.save(9571L, true, " *.csv ");
        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
        assertThat(this.sql.queryForMap("SELECT tenant_id, enabled, file_pattern, created_by FROM job_inbox_trigger WHERE job_id = 9571"))
            .containsEntry("tenant_id", A).containsEntry("enabled", true).containsEntry("file_pattern", "*.csv").containsEntry("created_by", 60L);
        assertThat(this.service.save(9571L, true, "intake/*.csv").getStatus()).isEqualTo("ERROR");
        assertThat(this.service.trigger(9571L).getData()).hasFieldOrPropertyWithValue("filePattern", "*.csv");

        TenantContext.clear();
        this.listener.onArrival(event());
        TenantContext.set(A, "TENANT_ADMIN", 60L, "admin@acme.example");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> arrivals = (List<Map<String, Object>>) this.service.arrivals(9571L, 20).getData();
        assertThat(arrivals).hasSize(1).first().satisfies(a -> {
            assertThat(a).containsEntry("arrivalId", "0b8f6a52-3f5e-4c1a-9d7e-5a4b3c2d1e0f").containsEntry("outcome", "Started")
                .containsEntry("fileName", "invoices_q3.csv");
            assertThat(a.get("jobQueueId")).isNotNull();
            assertThat((String) a.get("dateCreated")).endsWith("Z");
        });

        // A tenant user who neither made the job nor is assigned it sees no job at all -- as one that does not exist.
        TenantContext.set(A, "TENANT_USER", 77L, "other@acme.example");
        assertThat(this.service.trigger(9571L).getMessage()).isEqualTo(InboxTriggerService.JOB_NOT_FOUND);
        assertThat(this.service.save(9571L, false, null).getMessage()).isEqualTo(InboxTriggerService.JOB_NOT_FOUND);
        assertThat(this.service.arrivals(9571L, 20).getMessage()).isEqualTo(InboxTriggerService.JOB_NOT_FOUND);
        // The one who made it may.
        TenantContext.set(A, "TENANT_USER", 61L, "maker@acme.example");
        assertThat(this.service.save(9571L, false, null).getStatus()).isEqualTo("SUCCESS");
        assertThat(this.sql.queryForObject("SELECT enabled FROM job_inbox_trigger WHERE job_id = 9571", Boolean.class)).isFalse();

        // Another workspace's administrator: not found, and nothing changed.
        TenantContext.set(B, "TENANT_ADMIN", 70L, "admin@beta.example");
        assertThat(this.service.trigger(9571L).getMessage()).isEqualTo(InboxTriggerService.JOB_NOT_FOUND);
        assertThat(this.service.delete(9571L).getMessage()).isEqualTo(InboxTriggerService.JOB_NOT_FOUND);
        assertThat(this.sql.queryForObject("SELECT count(*) FROM job_inbox_trigger WHERE job_id = 9571", Long.class)).isEqualTo(1);

        TenantContext.set(A, "TENANT_ADMIN", 60L, "admin@acme.example");
        assertThat(this.service.delete(9571L).getStatus()).isEqualTo("SUCCESS");
        assertThat(this.sql.queryForObject("SELECT count(*) FROM job_inbox_trigger WHERE job_id = 9571", Long.class)).isZero();
        assertThat(this.service.trigger(9571L).getData()).hasFieldOrPropertyWithValue("configured", false);
    }
}
