package process.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.util.EncryptionUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-115: the order a session is configured in is the defence. Resource limits first, then the
 * storage credentials, then the local filesystem is removed, and lock_configuration comes LAST, so
 * nothing that runs afterwards can undo any of it. A session that fails part-way through is not
 * locked down, so it is closed and never handed back. DuckDbLockdownTest proves the effects on a
 * real engine; this pins the order, which the effects alone cannot show.
 */
class DuckDbLockdownOrderTest {

    /** A factory whose sessions record every statement, and can fail at a chosen one. */
    private static final class Recording extends DuckDbSessionFactory {
        final List<String> executed = new ArrayList<>();
        final Connection session = mock(Connection.class);
        String failOn;

        Recording() throws SQLException {
            super(limits(), new EncryptionUtil());
            Statement statement = mock(Statement.class);
            when(this.session.createStatement()).thenReturn(statement);
            when(statement.execute(anyString())).thenAnswer(inv -> {
                String sql = inv.getArgument(0);
                this.executed.add(sql);
                if (this.failOn != null && sql.startsWith(this.failOn)) throw new SQLException("refused for the test: " + sql);
                return false;
            });
        }

        @Override
        Connection connect() {
            return this.session;
        }
    }

    private static AnalyticsLimits limits() {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "memoryLimit", "256MB");
        ReflectionTestUtils.setField(limits, "threads", 2);
        return limits;
    }

    private static StorageConnection minio() {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(1L);
        connection.setProvider(StorageProvider.MINIO);
        connection.setAlias("test-store");
        connection.setEndpoint("http://minio:9000");
        return connection;
    }

    @Test
    void limitsThenCredentialsThenNoLocalFilesThenTheLockLast() throws Exception {
        Recording factory = new Recording();

        assertThat(factory.open(minio())).isSameAs(factory.session);

        List<String> sql = factory.executed;
        int memory = indexOf(sql, "SET memory_limit"), threads = indexOf(sql, "SET threads"), secret = indexOf(sql, "CREATE ");
        int noLocal = indexOf(sql, "SET disabled_filesystems='LocalFileSystem'"), lock = indexOf(sql, "SET lock_configuration=true");
        assertThat(memory).isNotNegative();
        assertThat(threads).isGreaterThan(memory);
        assertThat(secret).isGreaterThan(threads);
        assertThat(noLocal).isGreaterThan(secret);
        assertThat(lock).isEqualTo(sql.size() - 1);                    // nothing after the lock
        assertThat(noLocal).isEqualTo(lock - 1);
        verify(factory.session, never()).close();
    }

    @Test
    void aSessionThatFailsPartWayIsClosedAndNeverHandedBack() throws Exception {
        for (String step : new String[] {"SET threads", "CREATE ", "SET disabled_filesystems", "SET lock_configuration"}) {
            Recording factory = new Recording();
            factory.failOn = step;

            assertThatThrownBy(() -> factory.open(minio())).as(step).isInstanceOf(SQLException.class);
            verify(factory.session).close();
            // Everything after the failing step was never run: the session never got as far as its lock.
            if (!step.startsWith("SET lock_configuration")) assertThat(factory.executed).as(step).doesNotContain("SET lock_configuration=true");
        }
    }

    private static int indexOf(List<String> sql, String prefix) {
        for (int i = 0; i < sql.size(); i++) if (sql.get(i).startsWith(prefix)) return i;
        return -1;
    }
}
