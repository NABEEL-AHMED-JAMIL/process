package process.outbox;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The outbox against a real Postgres (MIG-22): an event is written in the caller's transaction and
 * published after it commits, in order, once.
 *
 * Opt-in, like NotificationStorePostgresTest: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD.
 */
class OutboxPostgresTest {

    private static String serverUrl;
    private static String scratch;
    private static HikariDataSource dataSource;

    private JdbcTemplate sql;
    private TransactionTemplate transaction;
    private OutboxWriter writer;
    private final List<String> sent = new ArrayList<>();

    @BeforeAll
    static void createDatabase() throws Exception {
        serverUrl = System.getenv("NOTIFICATIONS_TEST_DB_URL");
        assumeTrue(serverUrl != null, "NOTIFICATIONS_TEST_DB_URL is not set");
        scratch = "outbox_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = admin(); Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + scratch);
        }
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(serverUrl.replaceAll("/[^/?]+(\\?.*)?$", "/" + scratch + "$1"));
        dataSource.setUsername(System.getenv("NOTIFICATIONS_TEST_DB_USER"));
        dataSource.setPassword(System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
        new JdbcTemplate(dataSource).execute(new String(Files.readAllBytes(Paths.get(
            "src/main/resources/db/changelog/changelog-sets/V53.0-platform-outbox/V53__platform_outbox.sql")), "UTF-8"));
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        if (dataSource == null) return;
        dataSource.close();
        try (Connection admin = admin(); Statement sql = admin.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS " + scratch);
        }
    }

    private static Connection admin() throws Exception {
        return DriverManager.getConnection(serverUrl, System.getenv("NOTIFICATIONS_TEST_DB_USER"), System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void setUp() {
        this.sql = new JdbcTemplate(dataSource);
        this.sql.execute("TRUNCATE platform_outbox RESTART IDENTITY");
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.writer = new OutboxWriter(this.sql);
        this.sent.clear();
    }

    @SuppressWarnings("unchecked")
    private KafkaTemplate<String, String> kafka(String failOnKey) {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        // The relay sends a ProducerRecord, so the event's traceId can travel as its X-Correlation-Id header (MIG-94).
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(call -> {
            ProducerRecord<String, String> record = call.getArgument(0);
            SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
            String key = record.key();
            if (key.equals(failOnKey)) {
                future.setException(new IllegalStateException("broker unavailable"));
            } else {
                synchronized (this.sent) {
                    this.sent.add(record.topic() + " " + key + " " + record.value());
                }
                future.set(null);
            }
            return future;
        });
        return kafka;
    }

    private OutboxRelay relay(KafkaTemplate<String, String> kafka) {
        return new OutboxRelay(this.sql, this.transaction, kafka);
    }

    @Test
    void anEventWrittenInARolledBackTransactionIsNeverPublished() {
        this.transaction.execute(status -> {
            this.writer.write("platform.job.status.v1", "run:1", UUID.randomUUID().toString(), "{\"n\":1}");
            status.setRollbackOnly();
            return null;
        });

        assertThat(this.relay(this.kafka(null)).drain()).isZero();
        assertThat(this.sent).isEmpty();
    }

    @Test
    void committedEventsArePublishedInTheOrderTheyWereWrittenAndOnlyOnce() {
        this.transaction.execute(status -> {
            this.writer.write("platform.job.status.v1", "run:7", UUID.randomUUID().toString(), "Queue");
            this.writer.write("platform.job.status.v1", "run:7", UUID.randomUUID().toString(), "Start");
            this.writer.write("platform.job.status.v1", "run:7", UUID.randomUUID().toString(), "Completed");
            return null;
        });
        OutboxRelay relay = this.relay(this.kafka(null));

        assertThat(relay.drain()).isEqualTo(3);
        assertThat(relay.drain()).isZero();
        assertThat(this.sent).containsExactly("platform.job.status.v1 run:7 Queue",
            "platform.job.status.v1 run:7 Start", "platform.job.status.v1 run:7 Completed");
        assertThat(this.sql.queryForObject("SELECT count(*) FROM platform_outbox WHERE published_at IS NULL", Integer.class)).isZero();
    }

    /** Nothing may overtake an event the broker refused, or the job's states arrive out of order. */
    @Test
    void aFailedSendStopsTheDrainAndIsRetriedFirst() {
        this.transaction.execute(status -> {
            this.writer.write("t", "a", UUID.randomUUID().toString(), "1");
            this.writer.write("t", "b", UUID.randomUUID().toString(), "2");
            this.writer.write("t", "c", UUID.randomUUID().toString(), "3");
            return null;
        });

        assertThat(this.relay(this.kafka("b")).drain()).isEqualTo(1);
        assertThat(this.sent).containsExactly("t a 1");
        assertThat(this.sql.queryForObject("SELECT attempts FROM platform_outbox WHERE message_key = 'b'", Integer.class)).isEqualTo(1);
        assertThat(this.sql.queryForObject("SELECT last_error FROM platform_outbox WHERE message_key = 'b'", String.class)).contains("broker unavailable");

        assertThat(this.relay(this.kafka(null)).drain()).isEqualTo(2);
        assertThat(this.sent).containsExactly("t a 1", "t b 2", "t c 3");
    }

    /** Two instances drain at once; each event still goes out exactly once, in order. */
    @Test
    void twoRelaysDrainingTogetherSendEachEventOnce() throws Exception {
        this.transaction.execute(status -> {
            for (int i = 0; i < 200; i++) this.writer.write("t", "run:9", UUID.randomUUID().toString(), String.valueOf(i));
            return null;
        });
        KafkaTemplate<String, String> kafka = this.kafka(null);
        OutboxRelay a = this.relay(kafka);
        OutboxRelay b = this.relay(kafka);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        pool.submit(() -> { go.await(); while (a.drain() > 0) { } return null; });
        pool.submit(() -> { go.await(); while (b.drain() > 0) { } return null; });
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        while (a.drain() > 0) { }

        assertThat(this.sent).hasSize(200);
        for (int i = 0; i < 200; i++) assertThat(this.sent.get(i)).isEqualTo("t run:9 " + i);
    }

    @Test
    void publishedRowsAreClearedAfterTheirRetention() {
        this.transaction.execute(status -> {
            this.writer.write("t", "old", UUID.randomUUID().toString(), "1");
            this.writer.write("t", "new", UUID.randomUUID().toString(), "2");
            return null;
        });
        OutboxRelay relay = this.relay(this.kafka(null));
        relay.drain();
        this.sql.update("UPDATE platform_outbox SET published_at = now() - interval '8 days' WHERE message_key = 'old'");

        assertThat(relay.purgePublished()).isEqualTo(1);
        assertThat(this.sql.queryForList("SELECT message_key FROM platform_outbox", String.class)).containsExactly("new");
    }
}
