package process.outbox;

/**
 * What becomes of a run once DispatchRelay has tried to hand it to its worker queue. Implemented by the
 * dispatcher (ProducerBulkEngine), which owns what a hand-off means for the run; called inside the
 * relay's transaction, so the outbox row and the run move together.
 *
 * @author Nabeel Ahmed
 */
public interface DispatchOutcomes {

    /** The broker took the run's message at this offset: the run moves to Start. */
    void published(long jobQueueId, int attempt, long offset);

    /** The broker would not take it: closed, or offered another attempt, as a transient failure. */
    void publishFailed(long jobQueueId, int attempt, Throwable cause);
}
