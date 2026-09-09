package process.analytics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.security.TenantContext;

import java.lang.reflect.Method;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which storage failures are worth asking again about, and which are not.
 *
 * <b>Document 15's "retry policy for transient storage errors" row, and most of the value is in
 * the second half of this file.</b> Getting the retry to happen is easy; the expensive mistake is
 * retrying something that will fail identically, because the cost is a second full scan of the
 * same object charged to a governor that admits four queries at a time across the JVM. A wrong
 * "yes, retry" is paid for by every other reader on the system. A wrong "no" costs the error the
 * user was going to get anyway.
 *
 * The classifier is reached by reflection because it is private and static and has no business
 * being either public or an injected collaborator -- it is a table of substrings, and the test
 * that matters is which strings are in it.
 *
 * @author Nabeel Ahmed
 */
class AnalyticsStorageRetryTest {

    private static boolean transientFailure(String message) throws Exception {
        Method method = DuckDbAnalyticsEngine.class
            .getDeclaredMethod("isTransientStorageFailure", String.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, message.toLowerCase());
    }

    @Test
    void aResetOrRefusedConnectionIsWorthAnotherAsk() throws Exception {
        assertTrue(transientFailure("IO Error: Connection reset by peer"));
        assertTrue(transientFailure("HTTP Error: Failed to connect to minio:9000"));
        assertTrue(transientFailure("Could not establish connection to the endpoint"));
        assertTrue(transientFailure("IO Error: broken pipe"));
    }

    @Test
    void theStoreAskingToBeAskedLaterIsTheClearestCase() throws Exception {
        // 503 and 429 are not failures at all in the usual sense -- they are the object store
        // saying "not now". Not retrying these is the one case that is plainly wrong.
        assertTrue(transientFailure("HTTP 503 Service Unavailable"));
        assertTrue(transientFailure("HTTP 429 Too Many Requests"));
        assertTrue(transientFailure("SlowDown: please reduce your request rate"));
        assertTrue(transientFailure("HTTP 502 Bad Gateway"));
    }

    @Test
    void aRefusedCredentialIsNotRetried() throws Exception {
        // Fails identically the second time, and a second refused request against somebody else's
        // bucket is a second line in their access log.
        assertFalse(transientFailure("HTTP 403 Access Denied"));
        assertFalse(transientFailure("HTTP 401 Unauthorized"));
    }

    @Test
    void aMissingObjectIsNotRetried() throws Exception {
        assertFalse(transientFailure("IO Error: No files found that match the pattern"));
        assertFalse(transientFailure("HTTP 404 NoSuchKey"));
    }

    @Test
    void aBadStatementOrABadFileIsNotRetried() throws Exception {
        assertFalse(transientFailure("Parser Error: syntax error at or near \"slect\""));
        assertFalse(transientFailure("Invalid Input Error: could not convert string to DOUBLE"));
        assertFalse(transientFailure("CSV Error: sniffing failed"));
    }

    @Test
    void aTimeoutIsNotRetried() throws Exception {
        // The one that looks transient and is not. A query timeout means the work did not fit in
        // the ceiling; asking again spends another whole ceiling to fail in the same way. It is
        // also handled well before the classifier is reached -- this pins the classifier itself,
        // so that moving the checks around cannot quietly make timeouts retryable.
        assertFalse(transientFailure("Query timeout expired"));
        assertFalse(transientFailure("INTERRUPT Error: Interrupted!"));
    }

    @Test
    void memoryPressureIsNotRetried() throws Exception {
        // Deterministic given the same file and the same ceiling.
        assertFalse(transientFailure("Out of Memory Error: failed to allocate"));
        assertFalse(transientFailure("memory limit of 512MB exceeded"));
    }

    // ---- the loop, not the table -----------------------------------------------------------

    private static AnalyticsLimits retryLimits(int attempts) {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "timeoutSeconds", 5);
        ReflectionTestUtils.setField(limits, "maxRows", 100);
        ReflectionTestUtils.setField(limits, "previewPageSize", 100);
        ReflectionTestUtils.setField(limits, "maxConcurrentQueries", 2);
        ReflectionTestUtils.setField(limits, "memoryLimit", "256MB");
        ReflectionTestUtils.setField(limits, "threads", 1);
        ReflectionTestUtils.setField(limits, "storageRetryAttempts", attempts);
        // No wait: the test asserts how many attempts happen, not how long they take.
        ReflectionTestUtils.setField(limits, "storageRetryBackoffMs", 0L);
        return limits;
    }

    private static DatasetRef dataset() {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(9L);
        connection.setTenantId(1001L);
        connection.setProvider(StorageProvider.MINIO);
        connection.setAlias("store");
        connection.setBucketName("etl-bucket");
        connection.setStatus(Status.Active);
        return new DatasetRef(connection, "etl-bucket", "etl-demo/sales.csv", DatasetRef.Format.CSV);
    }

    @AfterEach
    void clearCaller() {
        TenantContext.clear();
    }

    /** A session factory that always fails to open, with the message given. */
    private static DuckDbSessionFactory failingWith(String message) throws Exception {
        DuckDbSessionFactory sessions = mock(DuckDbSessionFactory.class);
        when(sessions.open(any(StorageConnection.class)))
            .thenThrow(new SQLException(message));
        return sessions;
    }

    @Test
    void aTransientFailureIsAskedAgainExactlyOnce() throws Exception {
        TenantContext.set(1001L, "TENANT_USER", 7L, "analyst");
        DuckDbSessionFactory sessions = failingWith("IO Error: Connection reset by peer");
        DuckDbAnalyticsEngine engine =
            new DuckDbAnalyticsEngine(sessions, retryLimits(2), new RunningQueries());

        assertThrows(AnalyticsException.class, () -> engine.schemaOf(dataset()));

        // Two attempts, not three: the ceiling is attempts, not retries, and each one costs a
        // governor permit and a full scan.
        verify(sessions, times(2)).open(any(StorageConnection.class));
    }

    @Test
    void aRefusedCredentialIsNotAskedAgain() throws Exception {
        TenantContext.set(1001L, "TENANT_USER", 7L, "analyst");
        DuckDbSessionFactory sessions = failingWith("HTTP 403 Access Denied");
        DuckDbAnalyticsEngine engine =
            new DuckDbAnalyticsEngine(sessions, retryLimits(2), new RunningQueries());

        assertThrows(AnalyticsException.class, () -> engine.schemaOf(dataset()));

        // Once. A second refused request against somebody else's bucket is a second line in
        // their access log and cannot succeed.
        verify(sessions, times(1)).open(any(StorageConnection.class));
    }

    @Test
    void settingAttemptsToOneTurnsRetryOff() throws Exception {
        TenantContext.set(1001L, "TENANT_USER", 7L, "analyst");
        DuckDbSessionFactory sessions = failingWith("HTTP 503 Service Unavailable");
        DuckDbAnalyticsEngine engine =
            new DuckDbAnalyticsEngine(sessions, retryLimits(1), new RunningQueries());

        assertThrows(AnalyticsException.class, () -> engine.schemaOf(dataset()));

        verify(sessions, times(1)).open(any(StorageConnection.class));
    }

    @Test
    void theRegistryIsEmptyAfterEveryAttempt() throws Exception {
        // Each attempt takes its own handle. If a retry leaked one, the registry would grow by a
        // row per transient failure and the governor would eventually refuse everybody.
        TenantContext.set(1001L, "TENANT_USER", 7L, "analyst");
        RunningQueries running = new RunningQueries();
        DuckDbAnalyticsEngine engine = new DuckDbAnalyticsEngine(
            failingWith("IO Error: Connection reset by peer"), retryLimits(2), running);

        assertThrows(AnalyticsException.class, () -> engine.schemaOf(dataset()));

        assertEquals(0, running.size(), "a retried read must leave nothing in the registry");
    }
}
