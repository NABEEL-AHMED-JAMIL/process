package process.outbox;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.concurrent.SettableListenableFuture;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.ai.AiStepService;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.engine.BulkAction;
import process.engine.PreDispatchPhase;
import process.engine.ProducerBulkEngine;
import process.model.pojo.KafkaConnectionProfile;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.TaskReferenceRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.notifications.NotificationPort;
import process.security.RunCallbackTokens;
import process.util.OpenSearchAuditLogClient;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-134, MIG-136 and MIG-26 against a real Postgres (V86-V88): a queued run through every phase, with
 * the application's own components over Hibernate -- the pre-dispatch phase, the dispatcher, the
 * dispatch outbox and its relay -- and only the broker itself stubbed.
 *
 * T4: the callback token hash, the job_send latch and the outbox row commit in one local transaction, and
 * the publish happens after. So a dispatch that rolls back has published nothing, and a published run
 * always exists. The relay's outcome moves the run and the outbox row together, wipes the token from the
 * payload either way, and two relays never publish one row twice.
 *
 * Opt-in: runs when NOTIFICATIONS_TEST_DB_URL points at a Postgres server (see ScratchPostgres).
 */
class DispatchOutboxPostgresTest {

    private static final long TENANT = 9601L;
    private static final long USER = 96010L;
    private static final long JOB_ID = 9601L;

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private JdbcTemplate sql;
    private KafkaTemplate<String, String> broker;
    private PreDispatchPhase phase;
    private ProducerBulkEngine dispatcher;
    private DispatchRelay relay;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("dispatch_outbox");
        jpa = new ScratchJpa(db);
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
    @SuppressWarnings("unchecked")
    void wire() {
        this.sql = db.jdbc();
        this.sql.update("DELETE FROM dispatch_outbox");
        this.sql.update("DELETE FROM tenant_task_type_kafka_route WHERE tenant_id = ?", TENANT);
        this.sql.update("DELETE FROM kafka_connection_profile WHERE tenant_id = ?", TENANT);
        this.sql.update("DELETE FROM job_audit_logs");
        this.sql.update("DELETE FROM job_queue WHERE job_id = ?", JOB_ID);
        this.sql.update("DELETE FROM source_job WHERE job_id = ?", JOB_ID);
        this.sql.update("DELETE FROM source_task WHERE task_detail_id = 96011");
        this.sql.update("DELETE FROM source_task_type WHERE source_task_type_id = 96012");
        this.sql.update("DELETE FROM app_user WHERE app_user_id = ?", USER);
        this.sql.update("DELETE FROM tenant WHERE tenant_id = ?", TENANT);
        this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', 'T9601', 'Dispatch')", TENANT);
        this.sql.update("INSERT INTO app_user (app_user_id, full_name, password, status, user_role, username, tenant_id) "
            + "VALUES (?, 'Ops', 'x', 'Active', 'TENANT_USER', 'ops@dispatch.example', ?)", USER, TENANT);
        this.sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, "
            + "tenant_id, task_type_status) VALUES (96012, 'etl', 'etl', 'topic=etl.jobs&partitions=[*]', ?, 'Active')", TENANT);
        this.sql.update("INSERT INTO source_task (task_detail_id, task_name, task_status, task_payload, source_task_type_id, "
            + "tenant_id) VALUES (96011, 'claims', 'Active', '<pipeline><claim_id>CLM-1</claim_id></pipeline>', 96012, ?)", TENANT);
        this.sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, "
            + "assigned_user_id, task_detail_id, complete_job, fail_job, skip_job) "
            + "VALUES (?, ?, 'Manual', 'claims', 'Active', 1, ?, ?, 96011, false, false, false)",
            JOB_ID, Timestamp.valueOf(LocalDateTime.now().minusDays(1)), TENANT, USER);

        TransactionServiceImpl store = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class),
            jpa.repository(SchedulerRepository.class), jpa.repository(JobQueueRepository.class),
            jpa.repository(TaskReferenceRepository.class), jpa.repository(JobAuditLogRepository.class),
            jpa.repository(SourceTaskRepository.class), mock(OpenSearchAuditLogClient.class));
        BulkAction bulkAction = new BulkAction(store, mock(NotificationPort.class));
        AiStepService noAiSteps = mock(AiStepService.class);
        when(noAiSteps.apply(any(), any(), any(), anyString())).thenAnswer(inv -> new AiStepService.Outcome(inv.getArgument(3), null));
        DispatchOutbox outbox = new DispatchOutbox(this.sql);
        RunCallbackTokens tokens = new RunCallbackTokens(jpa.repository(JobQueueRepository.class), 24, "");
        this.phase = new PreDispatchPhase(store, bulkAction, noAiSteps, mock(JobMail.class), jpa.transactionManager());
        this.dispatcher = new ProducerBulkEngine(bulkAction, store, mock(JobMail.class), tokens, outbox, jpa.transactionManager());

        this.broker = mock(KafkaTemplate.class);
        KafkaTemplateProvider templates = mock(KafkaTemplateProvider.class);
        when(templates.getTemplate(any())).thenReturn(this.broker);
        this.relay = new DispatchRelay(this.sql, new TransactionTemplate(jpa.transactionManager()),
            mock(KafkaConnectionResolver.class), templates);
        this.relay.setOutcomes(this.dispatcher);
    }

    private void queuedRun(long jobQueueId) {
        this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status, job_send, attempt) "
            + "VALUES (?, ?, 'Queue', ?, ?, 'Active', false, 1)", jobQueueId, JOB_ID,
            Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)), Timestamp.valueOf(LocalDateTime.now().minusMinutes(1)));
    }

    private Map<String, Object> run(long jobQueueId) {
        return this.sql.queryForMap("SELECT * FROM job_queue WHERE job_queue_id = ?", jobQueueId);
    }

    private static SettableListenableFuture<SendResult<String, String>> tookItAt(long offset) {
        SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
        future.set(new SendResult<>(new ProducerRecord<>("etl.jobs", "k", "v"),
            new RecordMetadata(new TopicPartition("etl.jobs", 0), offset, 0L, 0L, 0L, 0, 0)));
        return future;
    }

    private static Map<String, String> headersOf(ProducerRecord<String, String> record) {
        Map<String, String> headers = new HashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), new String(header.value(), StandardCharsets.UTF_8));
        }
        return headers;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aQueuedRunIsPreparedDispatchedAndPublishedWithItsHeaders() {
        this.queuedRun(96001);
        when(this.broker.send(any(ProducerRecord.class))).thenReturn(tookItAt(77L));

        this.phase.runPass();
        assertThat(this.run(96001).get("prepared_at")).as("prepared").isNotNull();
        assertThat(this.run(96001).get("dispatch_payload")).isEqualTo("<pipeline><claim_id>CLM-1</claim_id></pipeline>");

        this.dispatcher.startJobInCurrentTimeSlot();
        Map<String, Object> dispatched = this.run(96001);
        assertThat(dispatched.get("job_send")).isEqualTo(true);
        assertThat(dispatched.get("callback_token_hash")).isNotNull();
        assertThat(dispatched.get("job_status")).as("written for sending, not yet handed over").isEqualTo("Queue");
        String correlationId = (String) dispatched.get("correlation_id");
        assertThat(correlationId).isNotNull();

        assertThat(this.relay.drain()).isEqualTo(1);

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(this.broker).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("etl.jobs");
        assertThat(sent.getValue().partition()).isNull();
        JsonObject message = JsonParser.parseString(sent.getValue().value()).getAsJsonObject();
        assertThat(message.get("jobQueueId").getAsLong()).isEqualTo(96001L);
        assertThat(message.get("callbackToken").getAsString()).startsWith("cbt_1.96001.");
        assertThat(headersOf(sent.getValue()))
            .containsEntry("x-tenant-id", String.valueOf(TENANT))
            .containsEntry("x-user-id", String.valueOf(USER))
            .containsEntry("X-Correlation-Id", correlationId);
        Map<String, Object> row = this.sql.queryForMap("SELECT * FROM dispatch_outbox WHERE job_queue_id = 96001");
        assertThat(row.get("published_at")).isNotNull();
        assertThat(row.get("record_offset")).isEqualTo(77L);
        assertThat(row.get("payload")).as("the token does not outlive the publish").isNull();
        Map<String, Object> started = this.run(96001);
        assertThat(started.get("job_status")).isEqualTo("Start");
        assertThat(started.get("job_status_message")).isEqualTo("Handed to the worker queue at offset 77.");
        assertThat(this.sql.queryForObject("SELECT job_running_status FROM source_job WHERE job_id = ?", String.class, JOB_ID))
            .isEqualTo("Start");
    }

    /**
     * The bug T4 fixes: a Kafka publish inside the transaction could succeed and then have its job_queue
     * row rolled back -- a dispatched run that does not exist. Here the dispatch's own write fails at the
     * last step: the token hash and the latch roll back with it, the run is closed, and nothing was
     * published, because nothing is published before commit.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aDispatchThatRollsBackHasPublishedNothing() {
        this.queuedRun(96002);
        this.phase.runPass();
        // Something already holds this run's attempt in the outbox: the dispatch's insert will be refused.
        this.sql.update("INSERT INTO dispatch_outbox (job_queue_id, attempt, topic, message_key, headers, published_at) "
            + "VALUES (96002, 1, 'etl.jobs', 'earlier', '{}', now())");

        this.dispatcher.startJobInCurrentTimeSlot();
        this.relay.drain();

        Map<String, Object> closed = this.run(96002);
        assertThat(closed.get("callback_token_hash")).as("rolled back with the outbox row").isNull();
        assertThat(closed.get("job_send")).isEqualTo(false);
        assertThat(closed.get("job_status")).as("one attempt allowed: closed, not left in Queue").isEqualTo("Failed");
        assertThat((String) closed.get("job_status_message")).startsWith("Job 9601 could not be dispatched: ");
        verify(this.broker, never()).send(any(ProducerRecord.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aBrokerThatRefusesClosesTheRunAndTheTokenGoesWithIt() {
        this.queuedRun(96003);
        SettableListenableFuture<SendResult<String, String>> refused = new SettableListenableFuture<>();
        refused.setException(new IllegalStateException("Topic etl.jobs not present in metadata"));
        when(this.broker.send(any(ProducerRecord.class))).thenReturn(refused);

        this.phase.runPass();
        this.dispatcher.startJobInCurrentTimeSlot();
        assertThat(this.relay.drain()).isZero();

        Map<String, Object> row = this.sql.queryForMap("SELECT * FROM dispatch_outbox WHERE job_queue_id = 96003");
        assertThat(row.get("abandoned_at")).isNotNull();
        assertThat(row.get("payload")).isNull();
        assertThat((String) row.get("last_error")).contains("Topic etl.jobs not present in metadata");
        Map<String, Object> closed = this.run(96003);
        assertThat(closed.get("job_status")).isEqualTo("Failed");
        assertThat(closed.get("job_status_message")).isEqualTo("Job 9601 could not be handed to the worker queue: "
            + "Topic etl.jobs not present in metadata");
    }

    /**
     * A broker that refuses one row is not asked again, this drain, about the rest of its connection's
     * rows -- they wait out their lease -- while another tenant's rows still go.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aRefusingBrokerDoesNotHoldUpAnotherTenantsRuns() {
        this.sql.update("INSERT INTO dispatch_outbox (job_queue_id, attempt, tenant_id, source_task_type_id, topic, message_key, "
            + "payload, headers) VALUES (98001, 1, 1, 1, 'down', 'a', '{}', '{}'), (98002, 1, 1, 1, 'down', 'b', '{}', '{}'), "
            + "(98003, 1, 2, 1, 'up', 'c', '{}', '{}')");
        SettableListenableFuture<SendResult<String, String>> refused = new SettableListenableFuture<>();
        refused.setException(new IllegalStateException("broker down"));
        when(this.broker.send(any(ProducerRecord.class))).thenAnswer(inv ->
            "down".equals(((ProducerRecord<String, String>) inv.getArgument(0)).topic()) ? refused : tookItAt(5L));

        int published = this.relay.drain();

        assertThat(published).isEqualTo(1);
        verify(this.broker, times(2)).send(any(ProducerRecord.class));
        Map<String, Object> waiting = this.sql.queryForMap("SELECT * FROM dispatch_outbox WHERE job_queue_id = 98002");
        assertThat(waiting.get("abandoned_at")).isNull();
        assertThat(waiting.get("published_at")).isNull();
        assertThat(waiting.get("claimed_until")).as("left to its lease").isNotNull();
        assertThat(this.sql.queryForObject("SELECT published_at FROM dispatch_outbox WHERE job_queue_id = 98003", Timestamp.class))
            .isNotNull();
    }

    /** A relay over the real resolver, on this database's routes and profiles; the templates stay stubbed. */
    @SuppressWarnings("unchecked")
    private DispatchRelay relayOverTheRealResolver(KafkaTemplateProvider templates) {
        KafkaConnectionResolver resolver = new KafkaConnectionResolver(jpa.repository(TenantTaskTypeKafkaRouteRepository.class),
            jpa.repository(SourceTaskTypeRepository.class), jpa.repository(KafkaConnectionProfileRepository.class));
        DispatchRelay relay = new DispatchRelay(this.sql, new TransactionTemplate(jpa.transactionManager()), resolver, templates);
        relay.setOutcomes(this.dispatcher);
        return relay;
    }

    private void aProfileOfTheWorkspacesOwn(boolean isDefault) {
        this.sql.update("INSERT INTO kafka_connection_profile (kafka_connection_profile_id, tenant_id, profile_name, "
            + "bootstrap_servers, security_protocol, is_default, status, date_created) "
            + "VALUES (96020, ?, 'Workspace brokers', 'ws-kafka:9092', 'PLAINTEXT', ?, 'Active', now())", TENANT, isDefault);
    }

    /** Characterisation (MIG-45, before): a workspace with Kafka of its own but no route still dispatches -- on the platform's. */
    @Test
    @SuppressWarnings("unchecked")
    void today_aWorkspaceWithItsOwnProfileButNoRouteIsDispatchedOnThePlatformDefault() {
        this.aProfileOfTheWorkspacesOwn(false);
        this.queuedRun(96004);
        when(this.broker.send(any(ProducerRecord.class))).thenReturn(tookItAt(9L));
        KafkaTemplateProvider templates = mock(KafkaTemplateProvider.class);
        when(templates.getTemplate(any())).thenReturn(this.broker);

        this.phase.runPass();
        this.dispatcher.startJobInCurrentTimeSlot();
        assertThat(this.relayOverTheRealResolver(templates).drain()).isEqualTo(1);

        ArgumentCaptor<Optional<KafkaConnectionProfile>> used = ArgumentCaptor.forClass(Optional.class);
        verify(templates).getTemplate(used.capture());
        assertThat(used.getValue().map(KafkaConnectionProfile::getTenantId)).as("a platform profile").isEmpty();
        assertThat(this.run(96004).get("job_status")).isEqualTo("Start");
    }

    /** Tier 4 proper: a workspace with no Kafka of its own is dispatched on the platform default (V70.2's row). */
    @Test
    @SuppressWarnings("unchecked")
    void aWorkspaceWithNoProfilesOfItsOwnIsDispatchedOnThePlatformDefault() {
        this.queuedRun(96005);
        when(this.broker.send(any(ProducerRecord.class))).thenReturn(tookItAt(10L));
        KafkaTemplateProvider templates = mock(KafkaTemplateProvider.class);
        when(templates.getTemplate(any())).thenReturn(this.broker);

        this.phase.runPass();
        this.dispatcher.startJobInCurrentTimeSlot();
        assertThat(this.relayOverTheRealResolver(templates).drain()).isEqualTo(1);

        ArgumentCaptor<Optional<KafkaConnectionProfile>> used = ArgumentCaptor.forClass(Optional.class);
        verify(templates).getTemplate(used.capture());
        assertThat(used.getValue()).as("V70.2's platform default").isPresent();
        assertThat(used.getValue().get().getTenantId()).isNull();
        assertThat(used.getValue().get().getIsDefault()).isTrue();
        assertThat(this.run(96005).get("job_status")).isEqualTo("Start");
    }

    /** Two instances' relays draining at once: every row goes out exactly once. */
    @Test
    @SuppressWarnings("unchecked")
    void twoRelaysNeverPublishARowTwice() throws Exception {
        for (long id = 1; id <= 20; id++) {
            this.sql.update("INSERT INTO dispatch_outbox (job_queue_id, attempt, tenant_id, source_task_type_id, topic, "
                + "message_key, payload, headers) VALUES (?, 1, ?, 96012, 'etl.jobs', ?, '{}', '{}')", 97000 + id, TENANT, "k" + id);
        }
        ConcurrentLinkedQueue<String> sentKeys = new ConcurrentLinkedQueue<>();
        when(this.broker.send(any(ProducerRecord.class))).thenAnswer(inv -> {
            sentKeys.add(((ProducerRecord<String, String>) inv.getArgument(0)).key());
            Thread.sleep(20);
            return tookItAt(1L);
        });
        KafkaTemplateProvider templates = mock(KafkaTemplateProvider.class);
        when(templates.getTemplate(any())).thenReturn(this.broker);
        DispatchRelay other = new DispatchRelay(this.sql, new TransactionTemplate(jpa.transactionManager()),
            mock(KafkaConnectionResolver.class), templates);
        other.setOutcomes(this.dispatcher);
        ExecutorService instances = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> drains = new ArrayList<>();
            drains.add(instances.submit(this.relay::drain));
            drains.add(instances.submit(other::drain));
            int published = 0;
            for (Future<Integer> drain : drains) {
                published += drain.get(30, TimeUnit.SECONDS);
            }
            assertThat(published).isEqualTo(20);
        } finally {
            instances.shutdownNow();
        }
        assertThat(sentKeys).hasSize(20).doesNotHaveDuplicates();
    }

    /** V88: the fetch limit is Core's own setting now, read through the application's own query. */
    @Test
    void theFetchLimitIsReadFromOrchestrationSetting() {
        String value = jpa.transactions().execute(status ->
            jpa.repository(JobQueueRepository.class).findOrchestrationSetting("QUEUE_FETCH_LIMIT"));

        assertThat(value).isEqualTo("1000");
        assertThat(this.sql.queryForObject("SELECT count(*) FROM lookup_data WHERE lookup_type = 'QUEUE_FETCH_LIMIT'",
            Integer.class)).isZero();
        verify(this.broker, never()).send(anyString(), eq("x"), anyString());
    }
}
