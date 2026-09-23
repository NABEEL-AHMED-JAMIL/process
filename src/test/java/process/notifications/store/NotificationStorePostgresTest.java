package process.notifications.store;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * notifications_db as MIG-21 defines it, against a real Postgres: the schema its own changelog
 * builds, and a store whose every read and write is scoped to one tenant.
 *
 * Opt-in: runs when NOTIFICATIONS_TEST_DB_URL (a JDBC URL to any database on the server, e.g.
 * jdbc:postgresql://localhost:5433/postgres), NOTIFICATIONS_TEST_DB_USER and
 * NOTIFICATIONS_TEST_DB_PASSWORD are set. It creates a throwaway database and drops it after.
 */
class NotificationStorePostgresTest {

    private static final long TENANT_A = 2901L;
    private static final long TENANT_B = 2905L;
    private static final long ME = 3468L;

    private static String serverUrl;
    private static String scratch;
    private static HikariDataSource dataSource;
    private static NotificationStore store;

    @BeforeAll
    static void createDatabase() throws Exception {
        serverUrl = System.getenv("NOTIFICATIONS_TEST_DB_URL");
        assumeTrue(serverUrl != null, "NOTIFICATIONS_TEST_DB_URL is not set");
        scratch = "notif_store_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = adminConnection(); Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + scratch);
        }
        dataSource = NotificationsDatabase.pool(serverUrl.replaceAll("/[^/?]+(\\?.*)?$", "/" + scratch + "$1"),
            System.getenv("NOTIFICATIONS_TEST_DB_USER"), System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
        NotificationsDatabase.migrate(dataSource);
        store = new NotificationStore(dataSource);
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        if (dataSource == null) return;
        dataSource.close();
        try (Connection admin = adminConnection(); Statement sql = admin.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS " + scratch);
        }
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(serverUrl, System.getenv("NOTIFICATIONS_TEST_DB_USER"), System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
    }

    @BeforeEach
    void empty() {
        new JdbcTemplate(dataSource).execute("TRUNCATE notification");
    }

    private static Notification notice(long tenantId, long recipient, String title, LocalDateTime at) {
        Notification notification = new Notification();
        notification.setTenantId(tenantId);
        notification.setRecipientUserId(recipient);
        notification.setType(NotificationType.JOB_FAILED);
        notification.setSeverity(NotificationSeverity.ERROR);
        notification.setTitle(title);
        notification.setMessage("Nightly export failed.");
        notification.setLinkUrl("/jobList");
        notification.setDateCreated(at);
        return notification;
    }

    @Test
    void aRowWithNoTenantCannotExist() {
        assertThatThrownBy(() -> new JdbcTemplate(dataSource).update(
            "INSERT INTO notification (tenant_id, recipient_user_id, type, severity, title, is_read, date_created) "
                + "VALUES (NULL, 1, 'JOB_FAILED', 'ERROR', 'x', false, now())"))
            .hasMessageContaining("tenant_id");
        Notification unscoped = notice(0, ME, "x", LocalDateTime.now());
        unscoped.setTenantId(null);
        assertThatThrownBy(() -> store.insert(unscoped)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTenantLeadsEveryIndex() {
        List<String> indexes = new JdbcTemplate(dataSource).queryForList(
            "SELECT indexdef FROM pg_indexes WHERE tablename = 'notification' AND indexname <> 'notification_pkey'", String.class);
        assertThat(indexes).isNotEmpty().allSatisfy(index -> assertThat(index).contains("(tenant_id, recipient_user_id"));
    }

    @Test
    void anInboxIsNewestFirstAndCountsItsUnread() {
        Notification older = store.insert(notice(TENANT_A, ME, "older", LocalDateTime.of(2026, 9, 22, 9, 0)));
        store.insert(notice(TENANT_A, ME, "newer", LocalDateTime.of(2026, 9, 23, 9, 0)));

        Page<Notification> inbox = store.inbox(TENANT_A, ME, false, PageRequest.of(0, 10));
        assertThat(inbox.getContent()).extracting(Notification::getTitle).containsExactly("newer", "older");
        assertThat(inbox.getTotalElements()).isEqualTo(2);
        assertThat(older.getNotificationId()).isNotNull();
        assertThat(store.unreadCount(TENANT_A, ME)).isEqualTo(2);

        assertThat(store.markRead(TENANT_A, older.getNotificationId(), ME, LocalDateTime.now())).isEqualTo(1);
        assertThat(store.markRead(TENANT_A, older.getNotificationId(), ME, LocalDateTime.now())).isZero();
        assertThat(store.inbox(TENANT_A, ME, true, PageRequest.of(0, 10)).getContent()).extracting(Notification::getTitle).containsExactly("newer");
        assertThat(store.find(TENANT_A, older.getNotificationId()).get().isRead()).isTrue();
    }

    /** The tenant is part of every predicate: a row filed under another tenant is not there at all. */
    @Test
    void anotherTenantsRowIsInvisibleAndUntouchable() {
        Notification theirs = store.insert(notice(TENANT_B, ME, "theirs", LocalDateTime.now()));

        assertThat(store.inbox(TENANT_A, ME, false, PageRequest.of(0, 10)).getContent()).isEmpty();
        assertThat(store.unreadCount(TENANT_A, ME)).isZero();
        assertThat(store.find(TENANT_A, theirs.getNotificationId())).isEmpty();
        assertThat(store.markRead(TENANT_A, theirs.getNotificationId(), ME, LocalDateTime.now())).isZero();
        assertThat(store.markAllRead(TENANT_A, ME, LocalDateTime.now())).isZero();
        assertThat(store.unreadCount(TENANT_B, ME)).isEqualTo(1);
    }

    @Test
    void theRecipientIsAPredicateToo() {
        Notification someoneElses = store.insert(notice(TENANT_A, 2992L, "not mine", LocalDateTime.now()));
        assertThat(store.markRead(TENANT_A, someoneElses.getNotificationId(), ME, LocalDateTime.now())).isZero();
        assertThat(store.inbox(TENANT_A, ME, false, PageRequest.of(0, 10)).getContent()).isEmpty();
    }

    /** The move script builds the table before the service first starts; the changelog must accept that. */
    @Test
    void theChangelogAcceptsATableTheMoveScriptAlreadyBuilt() {
        JdbcTemplate sql = new JdbcTemplate(dataSource);
        // As the script leaves it: the table exists, and Liquibase has no record of building it.
        sql.update("DELETE FROM databasechangelog");

        NotificationsDatabase.migrate(dataSource);

        assertThat(sql.queryForList("SELECT exectype FROM databasechangelog", String.class)).containsExactly("MARK_RAN");
    }
}
