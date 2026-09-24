package process.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * etl_job built from nothing by its own changelog, as a new environment would build it.
 *
 * Opt-in, like NotificationStorePostgresTest: runs when NOTIFICATIONS_TEST_DB_URL and its user and
 * password point at a Postgres server; builds one throwaway database for the class (ScratchEtlJob) and
 * drops it after. A test that writes rows uses ids no other test here uses.
 */
class EtlJobChangelogPostgresTest {

    private static ScratchEtlJob db;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("etl_job_fresh", Arrays.asList("platformKafkaBootstrapServers=broker.platform.test:9092",
            "platformKafkaSecurityProtocol=PLAINTEXT"));
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void aFreshEtlJobHasNoNotificationTable() {
        JdbcTemplate sql = db.sql();
        // It lives in notifications_db now; an empty leftover here would invite someone to write to it.
        assertThat(sql.queryForObject("SELECT to_regclass('public.notification') IS NULL", Boolean.class)).isTrue();
        // And document_converter_task lives in media_db (MIG-41, V54).
        assertThat(sql.queryForObject("SELECT to_regclass('public.document_converter_task') IS NULL", Boolean.class)).isTrue();
        assertThat(sql.queryForObject("SELECT to_regclass('public.app_user') IS NOT NULL", Boolean.class)).isTrue();

        // MIG-53 (V55): an alias is unique within a workspace, and a platform name (no tenant)
        // is unique among platform rows -- NULLs must not be distinct here, or two platform rows
        // could claim one name, the hole bucket_credential has.
        // MIG-53 part b (V56): every analytics alias carries the connection's id beside it.
        for (String column : new String[] {"analytics_analysis.storage_connection_id", "analytics_benchmark_result.storage_connection_id",
            "analytics_dataset.storage_connection_id", "analytics_query.storage_connection_id", "analytics_query.second_storage_connection_id",
            "analytics_query_run.storage_connection_id", "analytics_query_run.second_storage_connection_id"}) {
            String[] parts = column.split("\\.");
            assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = ?",
                Integer.class, parts[0], parts[1])).as(column).isEqualTo(1);
        }
        // MIG-70 (V57): storage_connection is storage-service's now. The copy here is kept for its
        // retention period, read-only: every write is refused, and says where the table went.
        assertThat(sql.queryForObject("SELECT to_regclass('public.storage_connection') IS NOT NULL", Boolean.class)).isTrue();
        assertThatThrownBy(() -> sql.update("INSERT INTO storage_connection (storage_connection_id, tenant_id, alias, "
            + "connection_name, is_default, provider, status) VALUES (9001, NULL, 'x', 'c', false, 'MINIO', 'Active')"))
            .as("insert").hasMessageContaining("storage-service");
        assertThatThrownBy(() -> sql.update("UPDATE storage_connection SET alias = 'y'")).as("update").hasMessageContaining("storage-service");
        assertThatThrownBy(() -> sql.update("DELETE FROM storage_connection")).as("delete").hasMessageContaining("storage-service");
        // MIG-88/89 (V61): billing's tables are billing_db's now. The copies here are read-only, reads
        // still answer, and every kind of write -- a truncate included -- is refused naming billing_db.
        assertThat(sql.queryForObject("SELECT count(*) FROM invoice", Integer.class)).isZero();
        assertThatThrownBy(() -> sql.update("INSERT INTO billing_account (tenant_id) VALUES (9001)")).as("insert").hasMessageContaining("billing_db");
        assertThatThrownBy(() -> sql.update("UPDATE invoice SET status = 'paid'")).as("update").hasMessageContaining("billing_db");
        assertThatThrownBy(() -> sql.update("DELETE FROM payment")).as("delete").hasMessageContaining("billing_db");
        assertThatThrownBy(() -> sql.execute("TRUNCATE billing_document")).as("truncate").hasMessageContaining("billing_db");
        // MIG-128 (V62): Analytics Studio's tables are analytics_db's, and process holds no code for them.
        // Every one of the seven refuses every kind of write, naming analytics_db.
        for (String table : new String[] {"analytics_dataset", "analytics_query", "analytics_query_run", "analytics_analysis",
            "analytics_dashboard", "analytics_dashboard_widget", "analytics_benchmark_result"}) {
            assertThat(sql.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
            assertThatThrownBy(() -> sql.update("DELETE FROM " + table)).as("delete %s", table).hasMessageContaining("analytics_db");
            assertThatThrownBy(() -> sql.execute("TRUNCATE " + table + " CASCADE")).as("truncate %s", table).hasMessageContaining("analytics_db");
        }
        assertThatThrownBy(() -> sql.update("UPDATE analytics_dashboard SET dashboard_name = 'x'")).as("update").hasMessageContaining("analytics_db");
        // MIG-147 / MIG-150 (V63): the AI tables are ai_db's (ADR-020). A pipeline step names a prompt
        // by id, and new prompts exist only in ai_db, so pipeline_field.prompt_id keeps its bigint
        // and loses its foreign key (C2); the five copies here refuse every write, naming ai_db.
        assertThat(sql.queryForObject("SELECT count(*) FROM pg_constraint WHERE conrelid = 'pipeline_field'::regclass "
            + "AND confrelid = 'ai_prompt'::regclass", Integer.class)).as("C2 demoted").isZero();
        for (String table : new String[] {"ai_model_connection", "ai_prompt", "ai_prompt_version", "ai_prompt_run", "ai_agent"}) {
            assertThat(sql.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
            assertThatThrownBy(() -> sql.update("DELETE FROM " + table)).as("delete %s", table).hasMessageContaining("ai_db");
        }
    }

    /**
     * MIG-71 (V70.0): one scheduler per job and one default Kafka connection per tenant, and one for the
     * platform, held by the database rather than only by the service layer -- a second instance or a second
     * service writing either table broke both silently. Two sessions race here as two instances would.
     */
    @Test
    void oneSchedulerPerJobAndOneDefaultKafkaProfilePerOwner() throws Exception {
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (7101, 'Active', 'T71A', 'Seventy-one A')");
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (7102, 'Active', 'T71B', 'Seventy-one B')");
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (7101, now(), 'Auto', 'j', 'Active', 1, 7101)");
        String scheduler = "INSERT INTO scheduler (scheduler_id, frequency, job_id, start_date, start_time) VALUES (?, 'Daily', 7101, current_date, '09:00')";

        assertThat(race(scheduler, new Object[] {7101L}, scheduler, new Object[] {7102L}))
            .as("two instances scheduling one job").containsExactlyInAnyOrder("ok", "uk_scheduler_job_id");
        assertThat(sql.queryForObject("SELECT count(*) FROM scheduler WHERE job_id = 7101", Integer.class)).isEqualTo(1);

        String profile = "INSERT INTO kafka_connection_profile (kafka_connection_profile_id, bootstrap_servers, is_default, profile_name, "
            + "security_protocol, status, tenant_id) VALUES (?, 'b:9092', ?, ?, 'PLAINTEXT', 'Active', ?)";
        sql.update(profile, 7101L, true, "A default", 7101L);
        sql.update(profile, 7102L, false, "A other", 7101L);
        sql.update(profile, 7103L, true, "B default", 7102L);
        // setAsDefault's two statements -- demote the others, promote this one -- run by two instances at
        // once for two different profiles of the same tenant. Before the index both committed.
        String demote = "UPDATE kafka_connection_profile SET is_default = false WHERE tenant_id = 7101 AND kafka_connection_profile_id <> ?";
        String promote = "UPDATE kafka_connection_profile SET is_default = true WHERE kafka_connection_profile_id = ?";
        sql.update(demote, 7101L);
        assertThat(race(promote, new Object[] {7101L}, promote, new Object[] {7102L}))
            .as("two instances promoting").containsExactlyInAnyOrder("ok", "ux_kcp_one_default_per_tenant");
        assertThat(sql.queryForObject("SELECT count(*) FROM kafka_connection_profile WHERE tenant_id = 7101 AND is_default",
            Integer.class)).isEqualTo(1);
        // Another tenant's default is its own business, and a non-default is never limited.
        sql.update(profile, 7104L, false, "B other", 7102L);
        // The platform (no tenant) gets exactly one too: NULLs must not make every platform row distinct.
        // V70.2 seeded the platform's default, so a second is refused outright.
        assertThatThrownBy(() -> sql.update(profile, 7106L, true, "Platform two", null))
            .hasMessageContaining("ux_kcp_one_platform_default");
    }

    /**
     * Runs two statements in two sessions at once, each in its own transaction, both started before
     * either commits. Returns "ok" or the name of the constraint that refused, per session.
     */
    private static List<String> race(String first, Object[] firstArgs, String second, Object[] secondArgs) throws Exception {
        List<String> outcomes = new ArrayList<>();
        try (Connection a = db.connect(); Connection b = db.connect()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);
            outcomes.add(attempt(a, first, firstArgs));
            ExecutorService other = Executors.newSingleThreadExecutor();
            try {
                // b blocks on a's uncommitted row until a commits, then fails -- or, without the constraint, succeeds.
                Future<String> blocked = other.submit(() -> attempt(b, second, secondArgs));
                Thread.sleep(300);
                a.commit();
                String outcome = blocked.get(10, TimeUnit.SECONDS);
                if ("ok".equals(outcome)) {
                    b.commit();
                } else {
                    b.rollback();
                }
                outcomes.add(outcome);
            } finally {
                other.shutdownNow();
            }
        }
        return outcomes;
    }

    private static String attempt(Connection session, String statement, Object[] args) {
        try (PreparedStatement prepared = session.prepareStatement(statement)) {
            for (int i = 0; i < args.length; i++) {
                prepared.setObject(i + 1, args[i]);
            }
            prepared.executeUpdate();
            return "ok";
        } catch (SQLException refused) {
            return refused.getMessage().replaceAll("(?s).*constraint \"([^\"]+)\".*", "$1");
        }
    }

    /**
     * MIG-45 (V70.2): a database built from the changelog has a platform default Kafka connection on the
     * brokers the deployment is configured with, so the resolver's last tier finds a profile rather than
     * nothing -- and every dispatch stops going out on the auto-configured fallback template unannounced.
     * The resolver's tier-4 and tier-3 reads, as the repository issues them, find the seeded row.
     */
    @Test
    void aBuiltDatabaseHasAPlatformDefaultKafkaConnection() {
        JdbcTemplate sql = db.sql();
        List<Map<String, Object>> platformDefault = sql.queryForList("SELECT kafka_connection_profile_id, bootstrap_servers, "
            + "security_protocol FROM kafka_connection_profile WHERE tenant_id IS NULL AND is_default = true AND status = 'Active'");

        assertThat(platformDefault).hasSize(1);
        assertThat(platformDefault.get(0).get("kafka_connection_profile_id")).isEqualTo(1L);
        assertThat(platformDefault.get(0).get("bootstrap_servers")).isEqualTo("broker.platform.test:9092");
        assertThat(platformDefault.get(0).get("security_protocol")).isEqualTo("PLAINTEXT");
        // Below the sequence's first value (1000), so no profile the console creates can ever take its id.
        assertThat(sql.queryForObject("SELECT start_value FROM pg_sequences WHERE sequencename = 'kafka_connection_profile_seq'",
            Long.class)).isGreaterThan(1L);
        // A new tenant has no default of its own (tier 3 misses) and lands on the seeded platform default.
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (7201, 'Active', 'T72', 'Seventy-two')");
        assertThat(sql.queryForList("SELECT kafka_connection_profile_id FROM kafka_connection_profile WHERE tenant_id = 7201 "
            + "AND is_default = true AND status = 'Active'", Long.class)).isEmpty();
    }

    /** A secured platform broker needs credentials a changeset must not carry: nothing is seeded for it. */
    @Test
    void aSecuredPlatformBrokerIsNotSeeded() throws Exception {
        try (ScratchEtlJob secured = ScratchEtlJob.build("etl_job_sasl", Arrays.asList(
                "platformKafkaBootstrapServers=broker.secure.test:9094", "platformKafkaSecurityProtocol=SASL_SSL"))) {
            assertThat(secured.sql().queryForObject("SELECT count(*) FROM kafka_connection_profile", Integer.class)).isZero();
        }
    }

    /**
     * MIG-59: V25-V30 and V46 removed whole features on purpose. A database built from the changelog must
     * carry nothing of them -- no table, no sequence, no lookup family -- and no object still named for
     * task_form, the pipeline builder's old name (V43, finished by V70.4).
     */
    @Test
    void theDroppedFeaturesLeaveNothingBehind() {
        JdbcTemplate sql = db.sql();
        for (String relation : new String[] {"avatar_backup_20260824", "dynamic_form", "dynamic_form_field", "dynamic_form_submission",
            "dynamic_form_seq", "dynamic_form_field_seq", "dynamic_form_submission_seq", "query_definition", "query_schedule",
            "query_execution", "database_connection_profile", "query_definition_seq", "query_schedule_seq", "query_execution_seq",
            "database_connection_profile_seq", "pdf_highlighter_task", "pdf_highlighter_field", "pdf_highlighter_task_id_seq",
            "pdf_highlighter_field_id_seq", "task_form", "task_form_field", "task_form_source_seq"}) {
            assertThat(sql.queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, "public." + relation)).as(relation).isTrue();
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM lookup_data WHERE lookup_type IN ('PIPELINE_IDS', 'EMAIL_RECEIVER', 'AI_PROVIDER')",
            Integer.class)).isZero();
        assertThat(sql.queryForList("SELECT conname FROM pg_constraint WHERE conname LIKE '%task_form%' "
            + "UNION ALL SELECT relname FROM pg_class WHERE relname LIKE '%task_form%'", String.class)).isEmpty();
        assertThat(sql.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'fk_pipeline_field_pipeline' "
            + "AND confrelid = 'pipeline'::regclass", Integer.class)).isEqualTo(1);
    }
}
