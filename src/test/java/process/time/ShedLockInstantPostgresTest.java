package process.time;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import process.ScratchPostgres;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-163: shedlock's two columns are timestamptz (V100), and ShedLock -- configured as ProcessConfig configures it,
 * with no zone of its own -- writes the instants it means on a JVM that is not in Chicago.
 *
 * Before V100 it wrote the JVM's wall-clock into naive columns (etl_job's rows read 11:09 at 16:09 UTC), so a lock
 * written by a Chicago JVM and read by a UTC one looked five hours stale, and the other way round held for five
 * hours. V100 converts what is there as Chicago; this is what gets written afterwards.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchPostgres).
 */
class ShedLockInstantPostgresTest {

    private static ScratchPostgres db;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("shedlock_instant");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void aLockHeldForTenMinutesEndsTenMinutesFromNow() {
        JdbcTemplateLockProvider provider = new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(db.jdbc()).build());
        Optional<SimpleLock> lock = provider.lock(new LockConfiguration("v100-instant", Duration.ofMinutes(10), Duration.ZERO));
        assertThat(lock).isPresent();
        Double minutes = db.jdbc().queryForObject("SELECT extract(epoch FROM (lock_until - now())) / 60 FROM shedlock WHERE name = 'v100-instant'",
            Double.class);
        assertThat(minutes).isBetween(9.0, 11.0);
        // A second taker sees it held, and once released it is free again.
        assertThat(provider.lock(new LockConfiguration("v100-instant", Duration.ofMinutes(10), Duration.ZERO))).isEmpty();
        lock.get().unlock();
        assertThat(provider.lock(new LockConfiguration("v100-instant", Duration.ofMinutes(10), Duration.ZERO))).isPresent();
    }
}
