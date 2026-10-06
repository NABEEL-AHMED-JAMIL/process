package process.schema;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-17 against a real Postgres: the index V64 adds, and the migration's refusal to run over existing
 * collisions. (The two finders that went through it left process with sign-in and account creation, MIG-108.)
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see IdentityPostgres).
 */
class UsernameUniquenessPostgresTest {

    private static void user(JdbcTemplate sql, long id, String username, String status) {
        sql.update("INSERT INTO app_user (app_user_id, full_name, password, status, user_role, username) "
            + "VALUES (?, 'x', 'x', ?, 'PLATFORM_ADMIN', ?)", id, status, username);
    }

    @Test
    void aNameThatDiffersOnlyByCaseCannotBeStoredWhateverTheStatus() throws Exception {
        try (IdentityPostgres db = IdentityPostgres.create("mig17_index").migrate()) {
            JdbcTemplate sql = db.sql();
            assertThat(sql.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_app_user_username_lower'",
                String.class)).contains("UNIQUE").contains("lower((username)::text)");

            user(sql, 9001, "Alice@X.com", "Active");
            assertThatThrownBy(() -> user(sql, 9002, "alice@x.com", "Active"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ux_app_user_username_lower");
            // A deleted row keeps its name, as the exact-case constraint always made it.
            user(sql, 9003, "bob@x.com", "Delete");
            assertThatThrownBy(() -> user(sql, 9004, "BOB@x.com", "Active"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ux_app_user_username_lower");
        }
    }

    @Test
    void theMigrationHaltsWhileCollisionsRemainAndSaysHowToFindThem() throws Exception {
        try (IdentityPostgres db = IdentityPostgres.create("mig17_halt").migrate()) {
            JdbcTemplate sql = db.sql();
            // Back to the database as it stood before V64, holding the collision V64 exists to forbid.
            sql.execute("DROP INDEX ux_app_user_username_lower");
            sql.update("DELETE FROM databasechangelog WHERE id = '64.0-username-unique-ignoring-case'");
            user(sql, 9001, "Carol@X.com", "Active");
            user(sql, 9002, "carol@x.com", "Inactive");

            assertThatThrownBy(db::migrate).hasStackTraceContaining("V64 cannot make usernames unique ignoring case");
            assertThat(sql.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_app_user_username_lower'",
                Integer.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM app_user WHERE lower(username) = 'carol@x.com'",
                Integer.class)).as("nothing was deleted to make room").isEqualTo(2);
        }
    }
}
