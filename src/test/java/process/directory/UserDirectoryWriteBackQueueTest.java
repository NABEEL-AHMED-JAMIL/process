package process.directory;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * MIG-214: with the database stalled, write-backs wait on one thread. The queue they wait in is bounded, and what does
 * not fit is dropped (a later lookup asks Identity again) instead of queued without limit.
 */
class UserDirectoryWriteBackQueueTest {

    @Test
    void aStalledDatabaseDoesNotQueueWriteBacksWithoutLimit() throws Exception {
        CountDownLatch stalled = new CountDownLatch(1);
        // Every statement hangs, as against a database that stopped answering.
        JdbcTemplate jdbc = mock(JdbcTemplate.class, call -> {
            stalled.await(10, TimeUnit.SECONDS);
            return call.getMethod().getReturnType() == int.class ? 1 : null;
        });
        ThreadPoolExecutor writeBacks = UserDirectory.writeBackExecutor();
        UserDirectory directory = new UserDirectory(jdbc, writeBacks);
        try {
            for (int i = 0; i < 500; i++) {
                UserDirectory.Entry entry = new UserDirectory.Entry((long) i, 1L, "user" + i, "User " + i, "ACTIVE", Instant.now());
                assertThatCode(() -> directory.writeBack(Collections.singletonList(entry))).doesNotThrowAnyException();
            }
            assertThat(writeBacks.getQueue().size()).isLessThanOrEqualTo(UserDirectory.WRITE_BACK_QUEUE);
        } finally {
            stalled.countDown();
            writeBacks.shutdownNow();
        }
    }
}
