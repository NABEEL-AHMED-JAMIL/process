package process.filechat;

/**
 * For File Chat tests that are about something other than the lock: every acquire succeeds at
 * once and stays held. Anything about exclusion, leases or an unreachable Redis is tested against
 * the real RedisFileIndexLock (RedisFileIndexLockTest, FileChatIndexAcrossInstancesTest).
 */
public final class UncontendedFileIndexLock implements FileIndexLock {

    @Override
    public Held acquire(String bucket, String key, String etag) {
        return new Held() {
            @Override
            public boolean stillHeld() {
                return true;
            }

            @Override
            public void close() {
            }
        };
    }
}
