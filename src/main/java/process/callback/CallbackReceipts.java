package process.callback;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * What a worker callback has already been answered with, by run and idempotency key (MIG-33, MIG-18).
 *
 * Delivery between a worker and this service is at-least-once: a response lost on the way back makes
 * the worker send the same callback again, and a broker between the two makes that normal. The first
 * delivery claims its key in the same transaction as the writes it makes, and records the answer
 * before that transaction commits; any later delivery with the same key finds the claim and gets the
 * recorded answer back, with nothing written a second time -- no second audit line, no second email.
 *
 * A claim that loses a race waits rather than guesses: the database holds the second insert until the
 * first transaction ends, so the loser sees either the winner's recorded answer or, if the winner
 * rolled back, a free key it then claims itself.
 *
 * @author Nabeel Ahmed
 */
public interface CallbackReceipts {

    /**
     * How long a receipt is kept. A callback is only accepted while its run's token is good -- a day
     * from dispatch, and a day past the end of the run for a usage report -- so no delivery can need
     * a receipt older than two days. A week is that with room to spare.
     */
    int RETENTION_DAYS = 7;

    /** The answer a key was first given, and which callback it was given to. */
    final class Receipt {

        public final String request;
        public final String outcomeStatus;
        public final String outcomeMessage;

        public Receipt(String request, String outcomeStatus, String outcomeMessage) {
            this.request = request;
            this.outcomeStatus = outcomeStatus;
            this.outcomeMessage = outcomeMessage;
        }
    }

    /**
     * Claims the key for this run in the caller's transaction. Empty means the claim is this caller's
     * and the callback should be applied; a receipt means it was applied before and this is a replay.
     */
    Optional<Receipt> claim(Long jobQueueId, String key, String request, LocalDateTime receivedAt);

    /** Writes the answer onto the claim, in the same transaction that made it. */
    void record(Long jobQueueId, String key, String outcomeStatus, String outcomeMessage);

    /** A key's receipt, without claiming it. */
    Optional<Receipt> find(Long jobQueueId, String key);

    /** Deletes receipts received before the cutoff; answers how many. */
    int purgeReceivedBefore(LocalDateTime cutoff);

    /** For a service built by hand, as the tests build them: remembers nothing, so every call is a first. */
    CallbackReceipts NONE = new CallbackReceipts() {

        @Override
        public Optional<Receipt> claim(Long jobQueueId, String key, String request, LocalDateTime receivedAt) {
            return Optional.empty();
        }

        @Override
        public void record(Long jobQueueId, String key, String outcomeStatus, String outcomeMessage) {
        }

        @Override
        public Optional<Receipt> find(Long jobQueueId, String key) {
            return Optional.empty();
        }

        @Override
        public int purgeReceivedBefore(LocalDateTime cutoff) {
            return 0;
        }
    };
}
