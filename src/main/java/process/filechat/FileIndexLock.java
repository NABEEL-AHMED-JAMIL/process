package process.filechat;

/**
 * The one lock under which File Chat indexes a file version into OpenSearch (MIG-111).
 *
 * Keyed on bucket|key|etag, the same identity OpenSearch stores the chunks under, so a changed
 * file (a new etag) legitimately takes a different lock. Held across the whole cold-index step:
 * the re-check, the extraction (for audio, a fresh transcription -- the most expensive operation
 * on the platform), the chunking, the embedding and the delete-then-write into the index.
 *
 * It has to hold across every instance of process, not only inside one JVM: two replicas asked
 * about the same unindexed file must extract it once between them, not once each.
 *
 * @author Nabeel Ahmed
 */
public interface FileIndexLock {

    /**
     * Waits for the lock on this file version, and holds it until the returned {@link Held} is
     * closed -- or until the holder stops renewing it (its JVM died), when it comes back by itself.
     *
     * @throws Busy        when another holder still had it at the end of the wait. The caller must
     *                     not index or extract on the strength of that: the holder is alive and doing it.
     * @throws Unavailable when the lock cannot be asked at all. Never a quiet "go ahead": the caller
     *                     must not index without it.
     */
    Held acquire(String bucket, String key, String etag) throws Busy, InterruptedException;

    /** A held lock. Closing it more than once, or after its lease ran out, is harmless. */
    interface Held extends AutoCloseable {

        /**
         * Whether this holder still has the lock, asked of the lock itself rather than remembered.
         * False once the lease ran out underneath it (a pause longer than the lease, a Redis that
         * restarted): a holder that lost its lock must not write, because another may have taken it.
         */
        boolean stillHeld();

        @Override
        void close();
    }

    /** Another holder kept the lock for the whole wait. */
    final class Busy extends Exception {
        public Busy(String message) {
            super(message);
        }
    }

    /** The lock could not be asked. */
    final class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
