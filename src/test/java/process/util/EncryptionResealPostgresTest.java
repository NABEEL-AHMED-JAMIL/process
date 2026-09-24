package process.util;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MIG-5: the re-seal against a real Postgres. Every secret process stores ends up under the current
 * key and opens to what it was; what is not a secret is not touched; a second run changes nothing.
 *
 * Opt-in, like OutboxPostgresTest: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD.
 */
class EncryptionResealPostgresTest {

    private static String serverUrl;
    private static String scratch;
    private static HikariDataSource dataSource;

    private final String oldKey = newKey();
    private final String currentKey = newKey();
    private JdbcTemplate sql;
    private EncryptionUtil before;
    private EncryptionUtil rotated;

    @BeforeAll
    static void createDatabase() throws Exception {
        serverUrl = System.getenv("NOTIFICATIONS_TEST_DB_URL");
        assumeTrue(serverUrl != null, "NOTIFICATIONS_TEST_DB_URL is not set");
        scratch = "reseal_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = admin(); Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + scratch);
        }
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(serverUrl.replaceAll("/[^/?]+(\\?.*)?$", "/" + scratch + "$1"));
        dataSource.setUsername(System.getenv("NOTIFICATIONS_TEST_DB_USER"));
        dataSource.setPassword(System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
        new JdbcTemplate(dataSource).execute("CREATE TABLE lookup_data (lookup_id bigint PRIMARY KEY, lookup_value text, "
            + "is_encrypted boolean NOT NULL DEFAULT false); CREATE TABLE kafka_connection_profile (kafka_connection_profile_id "
            + "bigint PRIMARY KEY, sasl_password varchar(1000), ssl_keystore_password_enc varchar(1000), "
            + "ssl_key_password_enc varchar(1000), ssl_truststore_password_enc varchar(1000))");
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

    private static String newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static EncryptionUtil util(String legacy, String currentId, String current) {
        EncryptionUtil util = new EncryptionUtil();
        ReflectionTestUtils.setField(util, "base64Key", legacy);
        ReflectionTestUtils.setField(util, "currentKeyId", currentId);
        ReflectionTestUtils.setField(util, "currentKey", current);
        return util;
    }

    private EncryptionReseal reseal(EncryptionUtil encryption) {
        return new EncryptionReseal(this.sql, new DataSourceTransactionManager(dataSource), encryption);
    }

    @BeforeEach
    void seed() {
        this.sql = new JdbcTemplate(dataSource);
        this.sql.execute("TRUNCATE lookup_data, kafka_connection_profile");
        this.before = util(this.oldKey, null, null);
        this.rotated = util(this.oldKey, "p2026a", this.currentKey);
        this.sql.update("INSERT INTO lookup_data VALUES (1, ?, true), (2, 'plain-setting', false), (3, NULL, true)", this.before.encrypt("lookup-secret"));
        this.sql.update("INSERT INTO kafka_connection_profile VALUES (10, ?, NULL, NULL, ?), (11, ?, ?, ?, '')",
            this.before.encrypt("sasl-10"), this.before.encrypt("trust-10"),
            this.before.encrypt("sasl-11"), this.before.encrypt("keystore-11"), this.before.encrypt("key-11"));
    }

    private String at(String table, String column, String id, long row) {
        return this.sql.queryForObject(String.format("SELECT %s FROM %s WHERE %s = ?", column, table, id), String.class, row);
    }

    @Test
    void everyStoredSecretEndsUnderTheCurrentKeyAndOpensToWhatItWas() {
        EncryptionReseal.Report report = this.reseal(this.rotated).reseal();

        assertThat(report.resealed).isEqualTo(6);
        assertThat(report.current).isZero();
        assertThat(report.unreadable).isZero();
        // With the old key gone, every secret still opens: the leak is void and nothing was lost.
        EncryptionUtil afterRotation = util(null, "p2026a", this.currentKey);
        assertThat(afterRotation.decrypt(this.at("lookup_data", "lookup_value", "lookup_id", 1))).isEqualTo("lookup-secret");
        String p = "kafka_connection_profile_id";
        assertThat(afterRotation.decrypt(this.at("kafka_connection_profile", "sasl_password", p, 10))).isEqualTo("sasl-10");
        assertThat(afterRotation.decrypt(this.at("kafka_connection_profile", "ssl_truststore_password_enc", p, 10))).isEqualTo("trust-10");
        assertThat(afterRotation.decrypt(this.at("kafka_connection_profile", "sasl_password", p, 11))).isEqualTo("sasl-11");
        assertThat(afterRotation.decrypt(this.at("kafka_connection_profile", "ssl_keystore_password_enc", p, 11))).isEqualTo("keystore-11");
        assertThat(afterRotation.decrypt(this.at("kafka_connection_profile", "ssl_key_password_enc", p, 11))).isEqualTo("key-11");
        // What is not a secret, or holds nothing, is not touched.
        assertThat(this.at("lookup_data", "lookup_value", "lookup_id", 2)).isEqualTo("plain-setting");
        assertThat(this.at("lookup_data", "lookup_value", "lookup_id", 3)).isNull();
        assertThat(this.at("kafka_connection_profile", "ssl_key_password_enc", p, 10)).isNull();
        assertThat(this.at("kafka_connection_profile", "ssl_truststore_password_enc", p, 11)).isEmpty();
    }

    @Test
    void aSecondRunChangesNothing() {
        this.reseal(this.rotated).reseal();
        String sealed = this.at("lookup_data", "lookup_value", "lookup_id", 1);

        EncryptionReseal.Report again = this.reseal(this.rotated).reseal();

        assertThat(again.resealed).isZero();
        assertThat(again.current).isEqualTo(6);
        assertThat(this.at("lookup_data", "lookup_value", "lookup_id", 1)).isEqualTo(sealed);
    }

    /** A value no key opens is counted and left as it is; the rest are still re-sealed. */
    @Test
    void aValueNoKeyOpensIsCountedAndTheRestStillMove() {
        String stranger = util(newKey(), null, null).encrypt("sealed-elsewhere");
        this.sql.update("UPDATE kafka_connection_profile SET sasl_password = ? WHERE kafka_connection_profile_id = 10", stranger);

        EncryptionReseal.Report report = this.reseal(this.rotated).reseal();

        assertThat(report.unreadable).isEqualTo(1);
        assertThat(report.resealed).isEqualTo(5);
        assertThat(this.at("kafka_connection_profile", "sasl_password", "kafka_connection_profile_id", 10)).isEqualTo(stranger);
        assertThat(this.rotated.isCurrent(this.at("lookup_data", "lookup_value", "lookup_id", 1))).isTrue();
    }

    /**
     * Someone saves a new SASL password while a replica is re-sealing. The re-seal must wait for that
     * save and re-seal the NEW value; reading first and writing later would put the old password back.
     */
    @Test
    void aSecretSavedDuringTheResealIsKeptNotOverwrittenWithTheOldOne() throws Exception {
        String saved = this.rotated.encrypt("sasl-10-new");
        try (Connection editor = dataSource.getConnection()) {
            editor.setAutoCommit(false);
            try (PreparedStatement update = editor.prepareStatement(
                "UPDATE kafka_connection_profile SET sasl_password = ? WHERE kafka_connection_profile_id = 10")) {
                update.setString(1, saved);
                update.executeUpdate();
            }
            CompletableFuture<EncryptionReseal.Report> reseal = CompletableFuture.supplyAsync(() -> this.reseal(this.rotated).reseal());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (this.sql.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND wait_event_type = 'Lock'", Integer.class) == 0) {
                assertThat(System.nanoTime()).as("the re-seal reached the edited row").isLessThan(deadline);
                Thread.sleep(20);
            }
            editor.commit();
            reseal.get(10, TimeUnit.SECONDS);
        }
        assertThat(this.rotated.decrypt(this.at("kafka_connection_profile", "sasl_password", "kafka_connection_profile_id", 10)))
            .isEqualTo("sasl-10-new");
    }
}
